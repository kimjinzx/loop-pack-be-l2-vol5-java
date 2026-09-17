package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointModelTest {

    @DisplayName("포인트 계정을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("사용자id가 주어지면, 잔액 0으로 생성된다.")
        @Test
        void createsPoint_withZeroBalance() {
            // act
            PointModel point = new PointModel(1L);

            // assert
            assertThat(point.getUserId()).isEqualTo(1L);
            assertThat(point.getBalance()).isEqualTo(0L);
        }

        @DisplayName("사용자id가 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenUserIdIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new PointModel(null));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("포인트를 충전할 때, ")
    @Nested
    class Charge {
        @DisplayName("양수 금액이면, 잔액에 더해진다.")
        @Test
        void increasesBalance_whenAmountIsPositive() {
            // arrange
            PointModel point = new PointModel(1L);

            // act
            point.charge(1_000L);

            // assert
            assertThat(point.getBalance()).isEqualTo(1_000L);
        }

        @DisplayName("여러 번 충전하면, 누적된다.")
        @Test
        void accumulatesBalance_whenChargedMultipleTimes() {
            // arrange
            PointModel point = new PointModel(1L);

            // act
            point.charge(1_000L);
            point.charge(2_000L);

            // assert
            assertThat(point.getBalance()).isEqualTo(3_000L);
        }

        @DisplayName("0 이하의 금액이면, BAD_REQUEST 예외가 발생하고 잔액이 유지된다.")
        @Test
        void throwsBadRequestException_whenAmountIsNotPositive() {
            // arrange
            PointModel point = new PointModel(1L);

            // act
            CoreException zeroResult = assertThrows(CoreException.class, () -> point.charge(0L));
            CoreException negativeResult = assertThrows(CoreException.class, () -> point.charge(-1_000L));

            // assert
            assertThat(zeroResult.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(negativeResult.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(point.getBalance()).isEqualTo(0L);
        }
    }
}
