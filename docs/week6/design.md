# 주문 확정 이벤트와 구매 활동 설계 — Week 6

> 상태: **producer 구현 중 (2026-10-29)**. 이 문서는 이벤트 계약·경계·실패 지점·결정 사항을 정한다. consumer(구매 활동 저장)와 전달 실험은 구현하면서 4절 이후를 채운다.
> 5주차의 결제 흐름(`docs/week5/pg-contract.md`, `design.md` 9절)은 바꾸지 않고, 확정된 뒤의 사실을 이벤트로 내보내는 일만 더한다.

## 0. 범위

| 이번 주에 하는 것 | 하지 않는 것 |
|---|---|
| 검증된 PG SUCCESS로 주문이 **처음** CONFIRMED가 된 사실을 `order-events`로 발행 | 새 결제·상품 CRUD |
| `commerce-streamer`가 이벤트를 소비해 상품 구매 활동을 DB에 한 번만 저장 | 랭킹 API (9주차) |
| partition·consumer group·offset 실험으로 전달 상태 설명 | Outbox (7주차). DB와 broker가 원자적이지 않다는 사실만 재현해 남긴다 |

## 1. 흐름과 경계

```
검증된 PG SUCCESS
  → [TX B] 주문 최초 CONFIRMED + confirmedAt 기록 + 예약 소비 + 이벤트 객체 구성   ← DB commit
  → (commit 뒤) PaymentFacade가 발행                                                  ← 여기까지 commerce-api
  → Kafka order-events (key = orderId)
  → commerce-streamer record listener
  → [TX] 완료 기록 + 상품별 구매 활동 저장 → commit 뒤 ack                           ← commerce-streamer
```

| 경계 | 지키는 것 | 깨지는 시점 (실패 지점) |
|---|---|---|
| 주문 DB TX B | 주문 CONFIRMED·예약 소비·확정 시각이 함께 commit되거나 함께 rollback | rollback이면 이벤트가 만들어지지 않는다 |
| DB commit → broker | **없음.** 둘을 묶는 장치가 없다 | commit 뒤 send 전에 멈추거나 send가 실패하면 확정 주문은 남고 이벤트는 없다 (W7 Outbox의 출발점) |
| broker → consumer | 한 번 이상 전달 (at-least-once) | 처리 뒤 offset commit 전에 멈추면 같은 record가 다시 온다 |
| consumer DB TX | 완료 기록과 모든 품목이 함께 commit 또는 rollback | 중간 실패는 전체 rollback, 재전달로 다시 시도 |

이벤트가 필요한 이유: 주문 확정(commerce-api)과 구매 활동 집계(commerce-streamer)를 한 트랜잭션으로 묶으면 두 앱이 같은 DB·배포 단위에 묶이고 집계 지연이 결제 응답에 영향을 준다. 사실(확정)만 넘기고 집계는 각자 속도로 따라가게 한다.

## 2. 이벤트 계약

| 항목 | 계약 |
|---|---|
| topic | `order-events` (`order-event.topic-name`). 테스트는 고유 접미사를 붙여 격리 |
| eventType / schemaVersion | `OrderConfirmed` / `1` |
| key | 내부 orderId의 문자열 표현. 같은 주문은 같은 key |
| eventId | `order-confirmed:v1:{orderId}`. 한 주문의 단 한 번인 확정 사실에서 유도. 재발행·재전달마다 새로 만들지 않는다 |
| orderId / confirmedAt | 확정 주문 id / 최초 PG 성공 전이 시각(UTC, ISO-8601, 밀리초 정밀도). 저장된 값과 같다 |
| items | `productId`, `quantity`. 같은 상품은 합산해 하나로, 상품 id 오름차순, 수량 양수 |
| 담지 않는 것 | 카드·PG 거래 정보·사용자 정보 |

예시(주문 42):

```json
{
  "eventType": "OrderConfirmed",
  "schemaVersion": 1,
  "eventId": "order-confirmed:v1:42",
  "orderId": 42,
  "confirmedAt": "2026-09-07T01:00:00Z",
  "items": [{"productId": 7, "quantity": 2}, {"productId": 8, "quantity": 1}]
}
```

- 값은 합성 예시다. 실제 발행은 저장된 주문의 확정 시각·품목을 쓴다. 소비 시점에 상품을 다시 조회해 과거 사실을 덮어쓰지 않는다.
- 속성 순서·items 순서가 다르다고 다른 사실로 보지 않는다. 비교는 정규화한 값으로 한다(consumer).
- 고객이 이벤트를 발행하는 공개 endpoint는 만들지 않는다.

## 3. producer (commerce-api) 결정

### 3-1. 정책 결정 (2026-10-29 확정)

| 질문 | 결정 | 이유 |
|---|---|---|
| 발행 위치 | `PaymentFacade`가 결과 적용 TX가 commit된 **뒤** 직접 호출 | `settle()`은 트랜잭션 메서드라서 안에서 send하면 commit 전 발행이다. confirm·reconcile·callback이 모두 `applyCreate`/`applyLookup`을 거치므로 한 지점(`publishAfterCommit`)으로 모인다. after-commit listener보다 "이번에 처음 확정했는가"를 호출 결과로 바로 알 수 있다 |
| "처음 확정"의 판별 | `PaymentEffect.SUCCEEDED`일 때만 `PaymentTransactions.Applied.confirmedEvent`가 채워진다 | 시도가 이번 호출에서 종결로 바뀐 경우에만 SUCCEEDED다. 같은 결과를 다시 적용하면 NONE이라 새 이벤트가 없다 |
| `confirmedAt` 저장 | `OrderModel.confirmed_at`(Instant, null 허용). `completePgPayment`에서만 기록 | 결제 대기에서만 들어올 수 있어 한 번만 정해진다. 이 컬럼이 생기기 전에 POINT로 확정한 주문은 null이고 소급하지 않는다 |
| 이벤트 구성 시점 | TX 안에서 저장된 엔티티로 만든다 | commit 뒤 lazy 접근에 의존하지 않고, 소비 시점 조회로 사실을 바꾸지 않는다 |
| 발행 실패 표시 | 주문 결과는 저장된 대로 응답하고, 실패는 ERROR 로그(eventId·orderId·이유)로 드러낸다 | 이미 확정된 주문을 실패·미확인으로 되돌리거나 PG create를 다시 부르지 않는다. 재시도·보관은 W7 몫 |

### 3-2. 발행 규칙

| 상황 | 발행 |
|---|---|
| 검증된 PG SUCCESS로 처음 CONFIRMED (callback·reconcile·create 응답 어느 경로든) | 한 번 |
| DRAFT·PAYMENT_PENDING·UNKNOWN·확정 실패(PAYMENT_FAILED) | 안 함 |
| 확정 TX rollback | 안 함 (이벤트를 commit 전에 보내지 않는다) |
| 이미 종결된 같은 시도의 반복 적용 (callback·reconcile 중복) | 안 함 |
| 이전 POINT 확정 주문 | 안 함 (소급 없음, `confirmed_at`이 null) |

### 3-3. 계층

| 층 | 구성 | 비고 |
|---|---|---|
| domain.order | `OrderConfirmedEvent`, `OrderEventPublisher`(port), `OrderEventPublication`(Sent/Failed) | PG port(`PaymentGateway`)와 같은 방식. domain은 Kafka를 모른다 |
| application.payment | `PaymentFacade.publishAfterCommit`, `PaymentTransactions.Applied` | 발행기가 던져도 주문 응답을 바꾸지 않는다 |
| infrastructure.order | `KafkaOrderEventPublisher` | send 결과(topic·partition·offset)를 기다려 `Sent`로, 실패·timeout은 `Failed`로 돌려준다. 예외를 던지지 않는다 |

send를 기다리는 시간은 `order-event.send-timeout`(5초)이다. 기다리는 동안 DB 트랜잭션·재고 잠금은 이미 풀려 있다(commit 뒤).

## 4. Kafka 설정 확인과 정리

시작 저장소의 `kafka.yml`에서 고친 것:

| 항목 | 시작 상태 | 정리 |
|---|---|---|
| consumer value 역직렬화 | `value-serializer`로 적혀 있어 Spring Boot가 읽지 않는다 | `value-deserializer: ByteArrayDeserializer` |
| auto commit | `properties.enable-auto-commit`(하이픈 표기)은 Kafka 키가 아니다 | `consumer.enable-auto-commit: false`. 실제 `ConsumerConfig` 값은 listener 테스트에서 확인한다 |
| bootstrap 주소 | admin만 `kafka:9092`, 나머지는 `localhost:19092`로 섞여 있었다 | 하나의 호스트 주소만 쓴다 (컨테이너 내부 주소와 섞지 않는다) |
| producer | acks·멱등·timeout 미지정 | `acks=all`, `enable.idempotence=true`, `max.block.ms=5000`, `delivery.timeout.ms=30000` |

기존 batch listener factory(`BATCH_LISTENER`: batch·MANUAL·concurrency 3)는 바꾸지 않는다. 이번 이벤트용 record factory는 consumer 구현 때 별도로 추가한다.
테스트 broker는 `modules:kafka` testFixtures의 `KafkaBrokerFixture`(Testcontainers, 단일 노드)이고 처음 쓸 때만 컨테이너를 띄운다. 고유 topic·group을 쓰며 다른 실행의 topic·group을 지우지 않는다.

`acks=all`은 단일 broker에서 복제 장애 보장을 확인하지 못한다. 이 환경의 테스트가 증명하는 것은 "broker가 알려 준 위치에서 같은 record를 읽는다"까지다.

## 5. consumer (commerce-streamer) — 구현하며 채운다

- 저장: 소비 완료 기록(`eventId`·`orderId` 유일, 고정 payload 일치)과 상품 구매 활동(`orderId`·`productId`별 1행, `quantity`·`confirmedAt` 보존)을 같은 DB TX에서 commit한다.
- 같은 eventId·같은 사실의 재전달은 no-op, 같은 eventId의 다른 사실·같은 orderId의 다른 eventId는 충돌로 드러낸다. 중복 판단은 DB 유일 제약으로 한다.
- record listener(`MANUAL_IMMEDIATE`, concurrency 1, 중단형 error handler): DB commit 뒤 ack.

## 6. 테스트 (producer 쪽)

| 테스트 | 확인 |
|---|---|
| `OrderConfirmedEventTest` | `confirmedAt` 기록 규칙, 이벤트가 저장 사실에서 만들어짐, eventId·key 안정성, 품목 합산·정렬, 확정 시각 없는 주문 거부 |
| `OrderConfirmedPublicationTest` | 처음 확정에서 한 번 발행, 중복 적용 무발행, 대기·실패·rollback 무발행, 발행 실패 시 주문·예약·PG 호출 유지 |
| `KafkaOrderEventPublisherTest` | 실제 broker에 보내고 broker가 알려 준 위치에서 key·본문 읽기, 같은 key는 같은 partition, broker 불통 시 예외 없이 실패 반환 |

통합 테스트는 broker 자리에 발행 호출을 기록하는 `RecordingOrderEventPublisher`를 쓴다. 실제 broker 전송은 위 `KafkaOrderEventPublisherTest`가 맡는다.

## 7. 알려진 한계

- commit 뒤 send 전에 멈추거나 send가 실패하면 이벤트가 사라진다. 자동 재발행이 없고, 구매 활동이 누락된다. 이 구간을 재현해 W7(Outbox)의 출발점으로 남긴다.
- 단일 broker라 복제·리더 선출 장애에서의 전달 보장은 확인하지 못한다.
- `commerce-api`가 `modules:kafka`에 의존하게 되어 `KafkaConfig`(기존 batch factory 포함)도 로드된다. api에는 listener가 없어 영향이 없다.
