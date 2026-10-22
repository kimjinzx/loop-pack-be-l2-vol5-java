package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.payment.PgCreateResult;
import com.loopers.domain.payment.PgLookupResult;
import com.loopers.domain.payment.PgPaymentRequest;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.payment.UnknownReason;
import com.loopers.support.pg.TestPgContract;
import com.loopers.support.pg.TestPgRequest;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.support.pg.TestPgTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 adapter → 실제 HTTP → 테스트 PG fixture로 계약(docs/week5/pg-contract.md 3·4절, 9절 표)을 확인한다.
 * Spring 컨텍스트와 DB 없이 adapter를 직접 만든다. 응답 본문 해석의 세부 규칙은 {@link TestPgResponsesTest}가 맡는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentProviderContractTest {

    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final long AMOUNT = 7000L;
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final String CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback";
    private static final Duration CREATE_READ_TIMEOUT = Duration.ofMillis(500);
    private static final PgPaymentRequest REQUEST = new PgPaymentRequest(ORDER_ID, "TEST", CARD_NO, AMOUNT);

    private static TestPgServer pg;
    private TestPgPaymentGateway gateway;

    @BeforeAll
    static void startPg() {
        pg = TestPgServer.start();
    }

    @AfterAll
    static void stopPg() {
        pg.close();
    }

    @BeforeEach
    void setUp() {
        gateway = new TestPgPaymentGateway(properties(pg.baseUrl()));
    }

    @AfterEach
    void tearDown() {
        assertThat(pg.handlerErrors()).isEmpty();
        pg.reset();
    }

    @DisplayName("거래 생성을 요청할 때, ")
    @Nested
    class Create {

        @DisplayName("POST /api/v1/payments에 제공자 계정 헤더와 계약의 JSON 타입으로 보내고, callbackUrl은 설정값이다. (#1)")
        @Test
        void sendsContractRequest() {
            // act
            gateway.requestPayment(REQUEST);

            // assert
            TestPgRequest request = pg.requests().get(0);
            JsonNode body = request.json();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo(TestPgContract.PAYMENTS_PATH);
            assertThat(request.header(TestPgContract.ACCOUNT_HEADER)).isEqualTo(TestPgContract.PROVIDER_ACCOUNT);
            assertThat(request.header("Content-Type")).startsWith("application/json");
            assertThat(request.header("Accept")).isEqualTo("application/json");
            assertThat(body.get("orderId").isTextual()).isTrue();
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("cardType").isTextual()).isTrue();
            assertThat(body.get("cardNo").isTextual()).isTrue();
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(AMOUNT);
            assertThat(body.get("callbackUrl").asText()).isEqualTo(CALLBACK_URL);
        }

        @DisplayName("202를 받으면 PG가 만든 거래 키로 접수 결과를 돌려준다. 결제 성공이 아니다. (#2)")
        @Test
        void returnsAccepted_on202() {
            // act
            PgCreateResult result = gateway.requestPayment(REQUEST);

            // assert
            TestPgTransaction created = pg.transactionsOf(ORDER_ID).get(0);
            assertThat(result).isEqualTo(new PgCreateResult.Accepted(created.transactionKey()));
            assertThat(created.status()).isEqualTo(TestPgStatus.PENDING);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("계약의 정확한 422를 받으면 효과 0건 거절이고, PG에는 거래가 없다. (#3)")
        @Test
        void returnsRejectedNoEffect_onExact422() {
            // arrange
            pg.rejectNextCreate();

            // act
            PgCreateResult result = gateway.requestPayment(REQUEST);

            // assert
            assertThat(result).isEqualTo(new PgCreateResult.RejectedNoEffect());
            assertThat(pg.transactionCount()).isZero();
        }

        @DisplayName("422의 변형은 모두 결과 미확인이고 효과 0건 거절이 아니다. (#4)")
        @Test
        void treats422VariantsAsUnknown() {
            // arrange
            String exact = "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"" + ORDER_ID
                + "\",\"amount\":7000,\"transactionCreated\":false}";
            List<String> variants = List.of(
                "",
                exact.replace("TEST_PG_REJECTED_BEFORE_CREATE", "OTHER_CODE"),
                exact.replace("false", "true"),
                exact.replace("false", "\"false\""),
                exact.replace(",\"transactionCreated\":false", ""),
                exact.replace("7000", "\"7000\""),
                exact.replace(ORDER_ID, "LP-ORD-0000000099"),
                exact.replace("7000", "9000")
            );

            // act & assert
            for (String body : variants) {
                pg.respondNextCreate(422, body, false);
                assertThat(gateway.requestPayment(REQUEST)).as(body).isInstanceOf(PgCreateResult.Unknown.class);
            }
            assertThat(pg.createCount()).isEqualTo(variants.size());
        }

        @DisplayName("400·500, 깨진 JSON이나 다른 금액·문자열 금액의 202는 모두 결과 미확인이다. (#5)")
        @Test
        void treatsOtherResponsesAsUnknown() {
            // arrange
            String accepted = "{\"transactionKey\":\"TEST-TX-9999\",\"orderId\":\"" + ORDER_ID + "\",\"amount\":7000,\"status\":\"PENDING\"}";
            List<Object[]> responses = List.of(
                new Object[] {400, "{\"code\":\"" + TestPgContract.INVALID_REQUEST + "\"}"},
                new Object[] {500, "{\"code\":\"BOOM\"}"},
                new Object[] {202, "{"},
                new Object[] {202, accepted.replace("7000", "9000")},
                new Object[] {202, accepted.replace("7000", "\"7000\"")}
            );

            // act & assert
            for (Object[] response : responses) {
                pg.respondNextCreate((int) response[0], (String) response[1], false);
                assertThat(gateway.requestPayment(REQUEST)).as(response[0] + " " + response[1])
                    .isInstanceOf(PgCreateResult.Unknown.class);
            }
        }

        @DisplayName("500을 받았지만 PG에는 거래가 생긴 경우도 결과 미확인이다. (#6)")
        @Test
        void treats500WithCreatedTransactionAsUnknown() {
            // arrange
            pg.respondNextCreate(500, "{\"code\":\"BOOM\"}", true);

            // act
            PgCreateResult result = gateway.requestPayment(REQUEST);

            // assert
            assertThat(result).isEqualTo(new PgCreateResult.Unknown(UnknownReason.UNEXPECTED_STATUS));
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1);
        }

        @DisplayName("거래가 생긴 뒤 응답이 늦어 timeout되면 TIMEOUT 결과 미확인이고, create를 다시 보내지 않는다. (#7)")
        @Test
        void returnsUnknownTimeout_withoutResending_whenResponseIsHeld() {
            // arrange
            pg.holdNextCreateResponse();

            // act
            long startedAt = System.nanoTime();
            PgCreateResult result = gateway.requestPayment(REQUEST);
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

            // assert
            assertThat(result).isEqualTo(new PgCreateResult.Unknown(UnknownReason.TIMEOUT));
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1);
            assertThat(pg.createCount()).isEqualTo(1);
            assertThat(waited).isGreaterThanOrEqualTo(CREATE_READ_TIMEOUT).isLessThan(TestPgServer.MAX_HOLD);
        }

        @DisplayName("거래가 생긴 뒤 응답 없이 연결이 끊기면 CONNECTION_LOST 결과 미확인이고, create를 다시 보내지 않는다. (#8)")
        @Test
        void returnsUnknownConnectionLost_withoutResending_whenResponseIsDropped() {
            // arrange
            pg.dropNextCreateResponse();

            // act
            PgCreateResult result = gateway.requestPayment(REQUEST);

            // assert
            assertThat(result).isEqualTo(new PgCreateResult.Unknown(UnknownReason.CONNECTION_LOST));
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("PG에 연결하지 못해도 실패로 단정하지 않고 CONNECTION_LOST 결과 미확인으로 둔다. (명세서 11절 열린 결정 1)")
        @Test
        void returnsUnknownConnectionLost_whenPgIsDown() {
            // arrange
            TestPgServer stopped = TestPgServer.start();
            String deadUrl = stopped.baseUrl();
            stopped.close();
            TestPgPaymentGateway toDeadPg = new TestPgPaymentGateway(properties(deadUrl));

            // act
            PgCreateResult result = toDeadPg.requestPayment(REQUEST);

            // assert
            assertThat(result).isEqualTo(new PgCreateResult.Unknown(UnknownReason.CONNECTION_LOST));
        }
    }

    @DisplayName("거래를 조회할 때, ")
    @Nested
    class Lookup {

        @DisplayName("단건 조회는 GET /api/v1/payments/{key}로 묻고, 200은 거래 1건, 404는 0건이다. (#9)")
        @Test
        void findsByKey() {
            // arrange
            TestPgTransaction seeded = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.SUCCESS);

            // act
            PgLookupResult found = gateway.findTransaction(seeded.transactionKey());
            PgLookupResult missing = gateway.findTransaction("TEST-TX-NONE");

            // assert
            TestPgRequest request = pg.requests().get(0);
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo(TestPgContract.PAYMENTS_PATH + "/" + seeded.transactionKey());
            assertThat(request.header(TestPgContract.ACCOUNT_HEADER)).isEqualTo(TestPgContract.PROVIDER_ACCOUNT);
            assertThat(found).isEqualTo(new PgLookupResult.Transactions(List.of(
                new PgTransaction(seeded.transactionKey(), ORDER_ID, AMOUNT, PgTransactionStatus.SUCCESS))));
            assertThat(missing).isEqualTo(new PgLookupResult.Transactions(List.of()));
        }

        @DisplayName("주문별 조회는 0건·1건·여러 건을 그대로 돌려주고, query 값을 인코딩해 보낸다. (#10)")
        @Test
        void listsByOrder_andEncodesQuery() {
            // arrange
            TestPgTransaction first = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.FAILED);
            TestPgTransaction second = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.PENDING);
            TestPgTransaction special = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, "A B&C+D", AMOUNT, TestPgStatus.PENDING);

            // act
            PgLookupResult none = gateway.findTransactionsByOrder("LP-ORD-0000000099");
            PgLookupResult two = gateway.findTransactionsByOrder(ORDER_ID);
            PgLookupResult encoded = gateway.findTransactionsByOrder("A B&C+D");

            // assert
            assertThat(none).isEqualTo(new PgLookupResult.Transactions(List.of()));
            assertThat(keysOf(two)).containsExactly(first.transactionKey(), second.transactionKey());
            assertThat(keysOf(encoded)).containsExactly(special.transactionKey());
            assertThat(pg.requests().get(2).rawQuery()).isEqualTo("orderId=A%20B%26C%2BD");
        }

        @DisplayName("다른 제공자 계정의 거래는 단건·주문별 조회 모두에서 보이지 않는다. (#11)")
        @Test
        void doesNotSeeOtherAccountsTransactions() {
            // arrange
            TestPgTransaction others = pg.seedTransaction("999999", ORDER_ID, AMOUNT, TestPgStatus.SUCCESS);

            // act & assert
            assertThat(gateway.findTransaction(others.transactionKey())).isEqualTo(new PgLookupResult.Transactions(List.of()));
            assertThat(gateway.findTransactionsByOrder(ORDER_ID)).isEqualTo(new PgLookupResult.Transactions(List.of()));
        }
    }

    @DisplayName("생성·거절·timeout·연결 끊김을 거치는 동안 로그에 카드번호가 남지 않는다. (#18)")
    @Test
    void neverLogsCardNumber(CapturedOutput output) {
        // act
        gateway.requestPayment(REQUEST);
        pg.rejectNextCreate();
        gateway.requestPayment(REQUEST);
        pg.respondNextCreate(500, "{\"code\":\"BOOM\"}", true);
        gateway.requestPayment(REQUEST);
        pg.holdNextCreateResponse();
        gateway.requestPayment(REQUEST);
        pg.dropNextCreateResponse();
        gateway.requestPayment(REQUEST);

        // assert
        assertThat(output.getAll()).contains("PG 거래 생성 결과를 확인하지 못했다").doesNotContain(CARD_NO);
        assertThat(REQUEST.toString()).doesNotContain(CARD_NO);
    }

    private static List<String> keysOf(PgLookupResult result) {
        assertThat(result).isInstanceOf(PgLookupResult.Transactions.class);
        return ((PgLookupResult.Transactions) result).transactions().stream().map(PgTransaction::transactionKey).toList();
    }

    private static PgProperties properties(String baseUrl) {
        return new PgProperties(
            baseUrl, CALLBACK_URL, TestPgContract.PROVIDER_ACCOUNT,
            Duration.ofMillis(500), CREATE_READ_TIMEOUT, Duration.ofMillis(300)
        );
    }
}
