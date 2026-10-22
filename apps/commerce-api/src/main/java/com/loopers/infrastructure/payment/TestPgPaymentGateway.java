package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PgCreateResult;
import com.loopers.domain.payment.PgLookupResult;
import com.loopers.domain.payment.PgPaymentRequest;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.domain.payment.UnavailableReason;
import com.loopers.domain.payment.UnknownReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 로컬 테스트 PG 계약(docs/week5/pg-contract.md 3절)의 adapter.
 *
 * <ul>
 *   <li>create는 어떤 층에서도 다시 보내지 않는다. PG는 멱등 키를 받지 않아 다시 보내면 곧 두 번째 거래다.</li>
 *   <li>JDK {@code HttpClient}를 HTTP/1.1·프록시 없음으로 쓴다. 기본 HTTP/2는 평문 연결에 업그레이드 헤더를 붙이고,
 *       기본 프록시 선택기가 끼면 우리가 센 호출 수 밖에서 재전송·버퍼링이 일어날 수 있다 (명세서 5-2절).</li>
 *   <li>응답은 상태 코드와 본문 문자열로만 받고, 해석은 {@link TestPgResponses}가 맡는다.</li>
 *   <li>요청·응답 본문은 로그에 남기지 않는다. 카드번호가 들어 있기 때문이다.</li>
 * </ul>
 */
@Slf4j
@Component
public class TestPgPaymentGateway implements PaymentGateway {

    static final String PAYMENTS_PATH = "/api/v1/payments";
    static final String ACCOUNT_HEADER = "X-USER-ID";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient createClient;
    private final RestClient lookupClient;
    private final String callbackUrl;

    public TestPgPaymentGateway(PgProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(properties.connectTimeout())
            .build();
        this.createClient = restClient(httpClient, properties.createReadTimeout(), properties);
        this.lookupClient = restClient(httpClient, properties.lookupReadTimeout(), properties);
        this.callbackUrl = properties.callbackUrl();
    }

    @Override
    public PgCreateResult requestPayment(PgPaymentRequest request) {
        PgCreateResult result;
        try {
            PgResponse response = createClient.post()
                .uri(PAYMENTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(createBody(request))
                .exchange((httpRequest, httpResponse) -> PgResponse.read(httpResponse));
            result = TestPgResponses.createResult(response.status(), response.body(), request);
        } catch (RestClientException e) {
            result = new PgCreateResult.Unknown(isReadTimeout(e) ? UnknownReason.TIMEOUT : UnknownReason.CONNECTION_LOST);
        }
        if (result instanceof PgCreateResult.Unknown unknown) {
            log.warn("PG 거래 생성 결과를 확인하지 못했다. providerOrderId={}, reason={}", request.providerOrderId(), unknown.reason());
        }
        return result;
    }

    @Override
    public PgLookupResult findTransaction(String transactionKey) {
        return lookup(() -> lookupByKeyOnce(transactionKey));
    }

    @Override
    public PgLookupResult findTransactionsByOrder(String providerOrderId) {
        return lookup(() -> lookupByOrderOnce(providerOrderId));
    }

    private PgLookupResult lookup(Supplier<List<PgTransaction>> call) {
        try {
            return new PgLookupResult.Transactions(call.get());
        } catch (PgLookupException e) {
            log.warn("PG 거래를 조회하지 못했다. reason={}", e.reason());
            return new PgLookupResult.Unavailable(e.reason());
        }
    }

    // 단건 조회의 404는 "지금 보이는 거래가 없다"는 정상 응답이다
    private List<PgTransaction> lookupByKeyOnce(String transactionKey) {
        PgResponse response = exchangeLookup(() -> lookupClient.get()
            .uri(PAYMENTS_PATH + "/{transactionKey}", transactionKey)
            .exchange((httpRequest, httpResponse) -> PgResponse.read(httpResponse)));
        if (response.status() == 404) {
            return List.of();
        }
        requireOk(response);
        return TestPgResponses.transaction(TestPgResponses.readJson(response.body()))
            .map(List::of)
            .orElseThrow(() -> new PgRetryableException(UnavailableReason.INVALID_BODY));
    }

    // query 값은 URI 변수로 넘겨 인코딩한다 (공백·&·+가 그대로 섞이지 않게)
    private List<PgTransaction> lookupByOrderOnce(String providerOrderId) {
        PgResponse response = exchangeLookup(() -> lookupClient.get()
            .uri(builder -> builder.path(PAYMENTS_PATH).queryParam("orderId", "{orderId}").build(providerOrderId))
            .exchange((httpRequest, httpResponse) -> PgResponse.read(httpResponse)));
        requireOk(response);
        return TestPgResponses.transactions(TestPgResponses.readJson(response.body()))
            .orElseThrow(() -> new PgRetryableException(UnavailableReason.INVALID_BODY));
    }

    private static PgResponse exchangeLookup(Supplier<PgResponse> exchange) {
        try {
            return exchange.get();
        } catch (RestClientException e) {
            throw new PgRetryableException(isReadTimeout(e) ? UnavailableReason.TIMEOUT : UnavailableReason.CONNECTION_LOST);
        }
    }

    private static void requireOk(PgResponse response) {
        if (response.status() == 200) {
            return;
        }
        if (response.status() >= 500) {
            throw new PgRetryableException(UnavailableReason.SERVER_ERROR);
        }
        throw new PgNonRetryableException(UnavailableReason.UNEXPECTED_STATUS);
    }

    private String createBody(PgPaymentRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("orderId", request.providerOrderId());
        body.put("cardType", request.cardType());
        body.put("cardNo", request.cardNo());
        body.put("amount", request.amount());
        body.put("callbackUrl", callbackUrl);
        return body.toString();
    }

    // 응답 대기 시간을 넘긴 경우만 TIMEOUT이다. 연결 수립 시간 초과는 연결 실패로 본다
    private static boolean isReadTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static RestClient restClient(HttpClient httpClient, Duration readTimeout, PgProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
            .requestFactory(requestFactory)
            .baseUrl(properties.baseUrl())
            .defaultHeader(ACCOUNT_HEADER, properties.providerUserId())
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    /** 상태 코드와 본문 문자열. 본문이 없으면 빈 문자열이다. */
    private record PgResponse(int status, String body) {
        static PgResponse read(ClientHttpResponse response) throws IOException {
            return new PgResponse(response.getStatusCode().value(), new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
