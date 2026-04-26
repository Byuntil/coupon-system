# 고동시성 선착순 쿠폰 발급 시스템

Spring Boot 3 / Java 21 / Redis / MySQL / Redis Streams / k6

선착순 쿠폰 발급에서 재고 정합성, DB 병목 완화, 비동기 접수, 장애 복구 흐름을 단계별로 분리한 프로젝트.

---

## 기술 스택

| 분류         | 기술                                             |
|------------|------------------------------------------------|
| Language   | Java 21                                        |
| Framework  | Spring Boot 3.3.5, Spring Web, Spring Data JPA |
| Database   | MySQL 8.0, H2 test profile                     |
| Redis      | Lua, Streams, Pub/Sub, Lettuce                 |
| Build      | Gradle multi-module                            |
| Load test  | k6                                             |
| Infra      | Docker, Docker Compose                         |
| Monitoring | InfluxDB, Grafana                              |

---

## 전체 구조

```
coupon-system/
├── coupon-common/   # Entity, Repository, Redis service, shared domain logic
├── coupon-api/      # 발급 접수 API, 상태 조회, SSE, load-test admin API
└── coupon-consumer/ # Redis Stream consumer, DB finalization, recovery, DLQ
```
---

## 개선 Phase

| Phase   | 구조                                     | 핵심 포인트           | 대표 지표                                   |
|---------|----------------------------------------|------------------|-----------------------------------------|
| Phase 0 | DB-only + `PESSIMISTIC_WRITE`          | DB 비관적 락 기준선     | VU=1000, 591 TPS, p99 2,110ms           |
| Phase 1 | Redis Lua 재고 선차감 + DB 최종 반영            | 실패 요청의 DB 진입 차단  | VU=1000, 2,218 TPS, p99 804ms           |
| Phase 2 | 동일 조건 비교 실험                            | Redis 도입 효과 정량화  | TPS +275%, p99 -62%                     |
| Phase 3 | Redis Lua + Redis Stream + 단일 Consumer | API 접수와 DB 완료 분리 | 6,595 accepted/s, PEL 복구 검증             |
| Phase 4 | 전체 결과 정리 / 후속 고도화                      | 운영 지표와 확장 실험 분리  | Stream lag, XPENDING, multi-consumer 후보 |

---

## Phase별 구조

### Phase 1: DB-only

```
Client
  -> API
  -> DB row lock
  -> stock check / decrease
  -> issue insert
  -> response
```

| 항목     | 내용                                    |
|--------|---------------------------------------|
| 동시성 제어 | DB 비관적 락                              |
| 병목 위치  | HikariCP connection pool, DB row lock |
| 포화 지점  | VU=200 이후 약 590 TPS                   |
| 한계     | 모든 요청이 DB 커넥션 경쟁에 참여                  |

### Phase 2: Redis 원자 재고 차감

```
Client
  -> API
  -> Redis Lua stock check/decrease
  -> DB issue transaction
  -> response
```

| 항목       | 내용                             |
|----------|--------------------------------|
| 동시성 제어   | Redis Lua 원자 연산                |
| DB 진입 조건 | Redis 재고 차감 성공 요청              |
| 개선 포인트   | 실패 요청 fast-fail, DB 커넥션 점유 감소  |
| 남은 한계    | 성공 요청은 API thread 안에서 DB 완료 대기 |

### Phase 3: Redis Stream 비동기 발급

```
API path
  Redis Lua admission
  -> Stream XADD
  -> 202 Accepted

Consumer path
  XREADGROUP
  -> DB finalization
  -> Redis state transition
  -> XACK
```

| 구성             | 역할            |
|----------------|---------------|
| `issued` SET   | 발급 완료 사용자     |
| `inflight` SET | 접수 후 처리 중 사용자 |
| Redis Stream   | 접수된 발급 작업 큐   |
| Consumer       | DB 최종 반영      |
| Pub/Sub + SSE  | 완료 결과 전달      |
| PEL / XCLAIM   | ACK 누락 메시지 복구 |
| DLQ            | 재처리 실패 메시지 격리 |

### Phase 4: 후속 고도화

| 우선순위 | 항목                                            |
|------|-----------------------------------------------|
| 1    | Stream lag, XPENDING, consumer 처리량 지표         |
| 2    | ordering inversion 분석 자동화                     |
| 3    | single-consumer baseline 이후 multi-consumer 비교 |
| 4    | Redis 유실 / 재시작 시나리오 보강                        |
