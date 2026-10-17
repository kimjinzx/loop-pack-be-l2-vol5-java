package com.loopers.config;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class RedisFailureOptionsTest {

    @Autowired
    private LettuceConnectionFactory lettuceConnectionFactory;

    @DisplayName("Redis 연결은 명령·연결 대기 시간을 300ms로 제한하고, 연결이 끊긴 동안의 명령은 바로 실패시킨다.")
    @Test
    void limitsWaitingTimeWhenRedisIsSlowOrDisconnected() {
        // act
        LettuceClientConfiguration configuration = lettuceConnectionFactory.getClientConfiguration();
        ClientOptions options = configuration.getClientOptions().orElseThrow();

        // assert
        assertAll(
            () -> assertThat(configuration.getCommandTimeout()).isEqualTo(Duration.ofMillis(300)),
            () -> assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(300)),
            () -> assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
        );
    }
}
