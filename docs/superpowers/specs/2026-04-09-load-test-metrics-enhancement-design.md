# 부하 테스트 메트릭 강화 설계

> 작성일: 2026-04-09
> 목적: Phase 0 vs Phase 1 비교 문서의 지표 완성도 향상
> 관련 문서: `docs/report/phase1-vs-phase0-load-test-comparison.md`

---

## 배경

현재 비교 문서의 문제점:

- p50/p95가 일부 시나리오에서 누락됨
- 성공(201) 레이턴시만 기록되어 있고 실패(400/409) 레이턴시가 없음
- p99 개선의 원인 설명이 정성적 설명에 의존하며, HikariCP 커넥션 수치 같은 실증 데이터가 없음

---

## 목표

1. k6 스크립트에 실패 요청 레이턴시 Trend 추가
2. Spring Boot에 Micrometer InfluxDB 연동 추가
3. Grafana 대시보드에 HikariCP 패널 추가
4. 부하 테스트 재실행 후 완전한 수치로 비교 문서 업데이트

---

## 변경 범위

### 1. k6 스크립트 — `issueDurationFail` Trend 추가

**대상 파일:** `k6/phase0-db-only.js`, `k6/phase1-redis.js`

**변경 내용:**

```js
// 메트릭 선언부에 추가
const issueDurationFail = new Trend('issue_duration_fail', true);

// 400/409 분기에 추가
if (res.status === 400 || res.status === 409) {
    businessFailCounter.add(1);
    issueSuccessRate.add(false);
    systemErrorRate.add(false);
    issueDurationFail.add(res.timings.duration);  // ← 추가
    sleep(0.05);
    return;
}
```

**수집되는 지표:** `issue_duration_fail` p50 / p95 / p99

기존 `issueDurationAll`(전체)과 `issueDurationSuccess`(201만)에 더해 실패 경로의 레이턴시를 분리 측정함.

---

### 2. Spring Boot — Micrometer InfluxDB 연동

**`build.gradle`:**

```groovy
implementation 'io.micrometer:micrometer-registry-influxdb'
```

**`application-loadtest.yml`:**

```yaml
management:
  metrics:
    export:
      influx:
        uri: http://influxdb:8086
        db: k6           # 기존 k6 DB와 동일
        step: 10s
    tags:
      application: coupon-system
  endpoints:
    web:
      exposure:
        include: health,metrics,hikaricp
```

**자동 수집되는 HikariCP 지표:**

| 메트릭 | 설명 |
|--------|------|
| `hikaricp.connections.active` | 현재 사용 중인 커넥션 수 |
| `hikaricp.connections.pending` | 커넥션 대기 중인 스레드 수 |
| `hikaricp.connections.acquire` | 커넥션 획득 소요 시간 (히스토그램) |

---

### 3. Grafana 대시보드 — HikariCP 패널 추가

**대상 파일:** `grafana/dashboards/k6-load-testing.json`

추가할 패널 2개:

| 패널 이름 | InfluxDB 메트릭 | 목적 |
|-----------|-----------------|------|
| HikariCP Active Connections | `hikaricp_connections_active` | Phase 0/1 커넥션 점유 패턴 비교 |
| HikariCP Pending Threads | `hikaricp_connections_pending` | 대기 큐 포화 여부 확인 |

---

### 4. 비교 문서 새 구조

**대상 파일:** `docs/report/phase1-vs-phase0-load-test-comparison.md`

각 시나리오를 아래 세 개 하위 테이블로 분리:

#### 성공 요청 레이턴시 (HTTP 201)

| VU | Phase 0 p50 | Phase 0 p95 | Phase 0 p99 | Phase 1 p50 | Phase 1 p95 | Phase 1 p99 |
|----|:-----------:|:-----------:|:-----------:|:-----------:|:-----------:|:-----------:|
| ... | | | | | | |

> 메트릭 소스: `issue_duration_success`

#### 실패 요청 레이턴시 (HTTP 400/409)

| VU | Phase 0 p50 | Phase 0 p95 | Phase 0 p99 | Phase 1 p50 | Phase 1 p95 | Phase 1 p99 |
|----|:-----------:|:-----------:|:-----------:|:-----------:|:-----------:|:-----------:|
| ... | | | | | | |

> 메트릭 소스: `issue_duration_fail` (신규)
>
> **예상 패턴:** Phase 0 실패 = DB 비관적 락 획득 후 재고 확인 → 수십~수백ms. Phase 1 실패 = Redis 원자 연산 1회로 즉시 거절 → 개 자릿수 ms.

#### DB 자원 지표 (HikariCP)

| VU | Phase 0 active peak | Phase 1 active peak | Phase 0 pending peak | Phase 1 pending peak |
|----|:-------------------:|:-------------------:|:--------------------:|:--------------------:|
| ... | | | | |

> **분석 포인트:** 재고 소진 시점 이후 Phase 0는 active 커넥션이 max(10)에 고착되는 반면,
> Phase 1은 Redis fast-fail 전환 직후 active 커넥션이 급감하는 패턴이 수치로 드러남.
> 이것이 p99 개선의 직접적 원인.

---

## 측정 순서

1. 코드 변경 완료 (k6 + Spring + Grafana)
2. Docker Compose 재빌드 및 실행
3. Phase 0 / Phase 1 각 시나리오 순차 실행
4. Grafana에서 수치 추출 → 문서에 기입
5. 기존 비교 문서를 새 구조로 교체

---

## 범위 밖

- Redis Lettuce 커맨드 레이턴시 (이번 범위 제외, 추후 Phase 2에서 검토)
- JVM GC, 스레드 수 등 추가 JVM 지표
- Grafana 알람/alert 설정
