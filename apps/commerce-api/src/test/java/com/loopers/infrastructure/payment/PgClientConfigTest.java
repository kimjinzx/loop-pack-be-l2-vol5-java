package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml의 payment.pg 설정이 PgProperties로 바인딩되고 adapter가 PaymentGateway 빈으로 올라오는지 확인한다.
 * DB·Redis 자동 설정 없이 PG 설정 클래스만 올린다.
 */
@SpringBootTest(classes = {PgClientConfig.class, TestPgPaymentGateway.class}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PgClientConfigTest {

    @Autowired
    private PgProperties properties;

    @Autowired
    private PaymentGateway paymentGateway;

    @DisplayName("payment.pg 설정이 주소·제공자 계정·시간 상한으로 바인딩된다.")
    @Test
    void bindsPaymentPgProperties() {
        // assert
        assertThat(properties.baseUrl()).isEqualTo("http://127.0.0.1:8082");
        assertThat(properties.callbackUrl()).isEqualTo("http://127.0.0.1:8080/api/v1/payments/callback");
        assertThat(properties.providerUserId()).isEqualTo("135135");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.createReadTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.lookupReadTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @DisplayName("PaymentGateway 빈은 테스트 PG adapter다.")
    @Test
    void registersTestPgAdapterAsPaymentGateway() {
        // assert
        assertThat(paymentGateway).isInstanceOf(TestPgPaymentGateway.class);
    }
}
