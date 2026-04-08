# Load Test Metrics Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** k6 실패 레이턴시 메트릭 추가, Micrometer InfluxDB 연동으로 HikariCP 지표를 Grafana에 연결하여 Phase 0 vs Phase 1 비교 문서의 수치 완성도를 높인다.

**Architecture:** k6 커스텀 Trend(`issueDurationFail`)가 실패 요청 레이턴시를 InfluxDB에 기록하고, Spring Boot Micrometer가 HikariCP 커넥션 지표를 동일 InfluxDB에 푸시한다. Grafana 대시보드에 HikariCP 패널 2개를 추가해 부하 테스트 중 커넥션 포화 패턴을 시각화한다.

**Tech Stack:** k6, Spring Boot 3.3.5, Micrometer InfluxDB Registry, Grafana, InfluxDB 1.8

---

## 파일 변경 맵

| 파일 | 변경 유형 | 내용 |
|------|-----------|------|
| `k6/phase0-db-only.js` | 수정 | `issueDurationFail` Trend 추가 |
| `k6/phase1-redis.js` | 수정 | `issueDurationFail` Trend 추가 |
| `build.gradle` | 수정 | `micrometer-registry-influxdb` 의존성 추가 |
| `src/main/resources/application-loadtest.yml` | 수정 | Micrometer InfluxDB export 설정 추가 |
| `grafana/dashboards/k6-load-testing.json` | 수정 | HikariCP 패널 2개 추가 |

---

## Task 1: k6 phase0 스크립트에 실패 레이턴시 메트릭 추가

**Files:**
- Modify: `k6/phase0-db-only.js`

- [ ] **Step 1: `issueDurationFail` Trend 선언 추가**

`k6/phase0-db-only.js` 의 메트릭 선언 블록(line 19-20 근처)에 추가:

```js
const issueDurationAll     = new Trend('issue_duration_all',     true);
const issueDurationSuccess = new Trend('issue_duration_success', true);
const issueDurationFail    = new Trend('issue_duration_fail',    true);  // ← 추가
```

- [ ] **Step 2: 400/409 분기에 레이턴시 기록 추가**

`default function` 내 400/409 분기를 찾아 수정:

```js
if (res.status === 400 || res.status === 409) {
    businessFailCounter.add(1);
    issueSuccessRate.add(false);
    systemErrorRate.add(false);
    issueDurationFail.add(res.timings.duration);  // ← 추가
    sleep(0.05);
    return;
}
```

- [ ] **Step 3: 변경 확인**

```bash
grep -n 'issueDurationFail' k6/phase0-db-only.js
```

예상 출력:
```
21:const issueDurationFail    = new Trend('issue_duration_fail',    true);
96:    issueDurationFail.add(res.timings.duration);
```

- [ ] **Step 4: 커밋**

```bash
git add k6/phase0-db-only.js
git commit -m "feat(k6): phase0 실패 요청 레이턴시 메트릭 추가"
```

---

## Task 2: k6 phase1 스크립트에 실패 레이턴시 메트릭 추가

**Files:**
- Modify: `k6/phase1-redis.js`

- [ ] **Step 1: `issueDurationFail` Trend 선언 추가**

`k6/phase1-redis.js` 의 메트릭 선언 블록(line 19-20 근처)에 추가:

```js
const issueDurationAll     = new Trend('issue_duration_all',     true);
const issueDurationSuccess = new Trend('issue_duration_success', true);
const issueDurationFail    = new Trend('issue_duration_fail',    true);  // ← 추가
```

- [ ] **Step 2: 400/409 분기에 레이턴시 기록 추가**

`default function` 내 400/409 분기를 찾아 수정:

```js
if (res.status === 400 || res.status === 409) {
    businessFailCounter.add(1);
    issueSuccessRate.add(false);
    systemErrorRate.add(false);
    issueDurationFail.add(res.timings.duration);  // ← 추가
    sleep(0.05);
    return;
}
```

- [ ] **Step 3: 변경 확인**

```bash
grep -n 'issueDurationFail' k6/phase1-redis.js
```

예상 출력:
```
21:const issueDurationFail    = new Trend('issue_duration_fail',    true);
96:    issueDurationFail.add(res.timings.duration);
```

- [ ] **Step 4: 커밋**

```bash
git add k6/phase1-redis.js
git commit -m "feat(k6): phase1 실패 요청 레이턴시 메트릭 추가"
```

---

## Task 3: Micrometer InfluxDB 의존성 추가

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: 의존성 추가**

`build.gradle` 의 `//spring-starter` 블록 끝에 추가:

```groovy
dependencies {
    //spring-starter
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-test'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-influxdb'  // ← 추가
    // ... 나머지 의존성은 그대로
```

- [ ] **Step 2: 빌드 확인**

```bash
./gradlew dependencies --configuration runtimeClasspath | grep influx
```

예상 출력 (버전은 Spring Boot BOM 관리):
```
io.micrometer:micrometer-registry-influxdb:1.13.x
```

- [ ] **Step 3: 커밋**

```bash
git add build.gradle
git commit -m "feat: micrometer-registry-influxdb 의존성 추가"
```

---

## Task 4: Micrometer InfluxDB export 설정 추가

**Files:**
- Modify: `src/main/resources/application-loadtest.yml`

- [ ] **Step 1: influx export 설정 추가**

`application-loadtest.yml` 의 `management:` 블록을 아래와 같이 교체:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,hikaricp
  metrics:
    export:
      influx:
        uri: http://influxdb:8086
        db: k6
        step: 10s
        auto-create-db: false
    tags:
      application: coupon-system
```

> `db: k6` — 기존 k6 데이터와 동일 DB에 저장해 Grafana 단일 데이터소스로 조회 가능.
> `step: 10s` — 10초 간격으로 HikariCP 지표를 푸시. 30초 부하 구간에서 3회 이상 데이터 포인트 확보.
> `auto-create-db: false` — influxdb 컨테이너가 이미 `k6` DB를 생성해 두므로 false.

- [ ] **Step 2: 설정 확인**

```bash
grep -A 10 'metrics:' src/main/resources/application-loadtest.yml
```

예상 출력:
```yaml
  metrics:
    export:
      influx:
        uri: http://influxdb:8086
        db: k6
        step: 10s
        auto-create-db: false
    tags:
      application: coupon-system
```

- [ ] **Step 3: 커밋**

```bash
git add src/main/resources/application-loadtest.yml
git commit -m "feat: Micrometer InfluxDB export 설정 추가 (HikariCP 지표 연동)"
```

---

## Task 5: Grafana 대시보드에 HikariCP 패널 추가

**Files:**
- Modify: `grafana/dashboards/k6-load-testing.json`

> 현재 대시보드: 패널 9개, 마지막 패널 하단 y=42
> 추가 위치: y=42부터 h=10 두 패널 (각 w=12)

- [ ] **Step 1: 패널 JSON 추가**

`grafana/dashboards/k6-load-testing.json` 의 `"panels": [...]` 배열 끝(닫는 `]` 전)에 아래 두 패널을 추가:

```json
    ,{
      "datasource": "InfluxDB",
      "fieldConfig": {
        "defaults": {
          "custom": {
            "drawStyle": "line",
            "lineWidth": 2,
            "fillOpacity": 10,
            "pointSize": 5
          },
          "unit": "short",
          "min": 0,
          "max": 10
        },
        "overrides": []
      },
      "gridPos": { "h": 10, "w": 12, "x": 0, "y": 42 },
      "id": 10,
      "title": "HikariCP Active Connections",
      "type": "timeseries",
      "targets": [
        {
          "alias": "active",
          "query": "SELECT mean(\"value\") FROM \"hikaricp_connections_active\" WHERE $timeFilter GROUP BY time(5s) fill(null)",
          "rawQuery": true,
          "refId": "A",
          "resultFormat": "time_series"
        }
      ]
    },
    {
      "datasource": "InfluxDB",
      "fieldConfig": {
        "defaults": {
          "custom": {
            "drawStyle": "line",
            "lineWidth": 2,
            "fillOpacity": 10,
            "pointSize": 5
          },
          "unit": "short",
          "min": 0
        },
        "overrides": []
      },
      "gridPos": { "h": 10, "w": 12, "x": 12, "y": 42 },
      "id": 11,
      "title": "HikariCP Pending Threads",
      "type": "timeseries",
      "targets": [
        {
          "alias": "pending",
          "query": "SELECT mean(\"value\") FROM \"hikaricp_connections_pending\" WHERE $timeFilter GROUP BY time(5s) fill(null)",
          "rawQuery": true,
          "refId": "A",
          "resultFormat": "time_series"
        }
      ]
    }
```

- [ ] **Step 2: JSON 유효성 확인**

```bash
python3 -c "import json; json.load(open('grafana/dashboards/k6-load-testing.json')); print('JSON valid')"
```

예상 출력:
```
JSON valid
```

- [ ] **Step 3: 패널 수 확인**

```bash
python3 -c "
import json
d = json.load(open('grafana/dashboards/k6-load-testing.json'))
panels = d['panels']
print(f'패널 수: {len(panels)}')
for p in panels[-2:]:
    print(f'  id={p[\"id\"]} title={p[\"title\"]} gridPos={p[\"gridPos\"]}')
"
```

예상 출력:
```
패널 수: 11
  id=10 title=HikariCP Active Connections gridPos={'h': 10, 'w': 12, 'x': 0, 'y': 42}
  id=11 title=HikariCP Pending Threads gridPos={'h': 10, 'w': 12, 'x': 12, 'y': 42}
```

- [ ] **Step 4: 커밋**

```bash
git add grafana/dashboards/k6-load-testing.json
git commit -m "feat(grafana): HikariCP Active/Pending 패널 추가"
```

---

## Task 6: 통합 검증 — Docker Compose 재빌드 및 부하 테스트

- [ ] **Step 1: Docker Compose 재빌드**

```bash
docker compose down -v
docker compose build app
docker compose up -d
```

app 컨테이너 로그에서 Micrometer 초기화 확인:

```bash
docker compose logs app | grep -i influx
```

예상 출력 (약 30초 내):
```
Sending metrics to InfluxDB at http://influxdb:8086
```

- [ ] **Step 2: Phase 1 Scenario A 부하 테스트 실행 (VU=500)**

```bash
docker run --rm --network coupon-system_default \
  -e K6_INFLUXDB_ADDR=http://influxdb:8086 \
  -e K6_INFLUXDB_DB=k6 \
  grafana/k6 run - < k6/phase1-redis.js \
  -e VUS=500 -e STOCK=10000 -e BASE_URL=http://app:8080 \
  -e COUPON_CODE=VERIFY-PHASE1-001 \
  -o influxdb=http://influxdb:8086/k6
```

> 네트워크 이름은 `docker compose ls` 또는 `docker network ls`로 확인.

- [ ] **Step 3: InfluxDB에서 신규 메트릭 존재 확인**

```bash
docker compose exec influxdb influx -database k6 -execute \
  "SHOW MEASUREMENTS" | grep -E "issue_duration_fail|hikaricp"
```

예상 출력:
```
hikaricp_connections_active
hikaricp_connections_pending
issue_duration_fail
```

- [ ] **Step 4: Grafana에서 HikariCP 패널 확인**

브라우저에서 `http://localhost:3000` 접속 → k6 Load Testing 대시보드 → 하단 "HikariCP Active Connections", "HikariCP Pending Threads" 패널에 데이터 표시 확인.

- [ ] **Step 5: 실패 레이턴시 수치 추출 (InfluxDB 쿼리)**

```bash
docker compose exec influxdb influx -database k6 -execute \
  "SELECT percentile(value, 50), percentile(value, 95), percentile(value, 99) FROM issue_duration_fail ORDER BY time DESC LIMIT 1"
```

p50/p95/p99 수치가 반환되면 검증 완료.

---

## Task 7: 비교 문서 업데이트

> Phase 0 / Phase 1 각 시나리오를 모두 실행한 뒤 수치를 채운다.
> 실행 순서: Phase 0 Scenario A VU=200/500/1000 → Phase 0 Scenario B VU=500/1000 → Phase 1 동일 순서.

- [ ] **Step 1: 각 시나리오 실행 후 Grafana에서 수치 추출**

추출할 지표 목록:

| 메트릭 | InfluxDB 쿼리 예시 |
|--------|-------------------|
| 성공 p50/p95/p99 | `SELECT percentile(value,50), percentile(value,95), percentile(value,99) FROM issue_duration_success` |
| 실패 p50/p95/p99 | `SELECT percentile(value,50), percentile(value,95), percentile(value,99) FROM issue_duration_fail` |
| HikariCP active peak | `SELECT max(value) FROM hikaricp_connections_active` |
| HikariCP pending peak | `SELECT max(value) FROM hikaricp_connections_pending` |

- [ ] **Step 2: `docs/report/phase1-vs-phase0-load-test-comparison.md` 구조 교체**

각 시나리오(A, B) 하위에 다음 세 테이블로 분리:

**성공 요청 레이턴시 (HTTP 201) — `issue_duration_success`**

| VU | Phase 0 p50 | Phase 0 p95 | Phase 0 p99 | Phase 1 p50 | Phase 1 p95 | Phase 1 p99 |
|----|:-----------:|:-----------:|:-----------:|:-----------:|:-----------:|:-----------:|
| 200 | | | | | | |
| 500 | | | | | | |
| 1000 | | | | | | |

**실패 요청 레이턴시 (HTTP 400/409) — `issue_duration_fail`**

| VU | Phase 0 p50 | Phase 0 p95 | Phase 0 p99 | Phase 1 p50 | Phase 1 p95 | Phase 1 p99 |
|----|:-----------:|:-----------:|:-----------:|:-----------:|:-----------:|:-----------:|
| 200 | | | | | | |
| 500 | | | | | | |
| 1000 | | | | | | |

> 예상 패턴: Phase 0 실패 = DB 비관적 락 획득 후 재고 확인 → 수십~수백ms.
> Phase 1 실패 = Redis Lua 스크립트 1회로 즉시 거절 → 한 자릿수~수십ms.

**DB 자원 지표 (HikariCP) — `hikaricp_connections_active` / `hikaricp_connections_pending`**

| VU | Phase 0 active peak | Phase 1 active peak | Phase 0 pending peak | Phase 1 pending peak |
|----|:-------------------:|:-------------------:|:--------------------:|:--------------------:|
| 200 | | | | |
| 500 | | | | |
| 1000 | | | | |

> 분석 포인트: 재고 소진 시점 이후 Phase 0는 active 커넥션이 max(10)에 고착되는 반면,
> Phase 1은 Redis fast-fail 전환 직후 active 커넥션이 급감. 이것이 p99 개선의 직접 원인.

- [ ] **Step 3: 분석 섹션 업데이트**

HikariCP 수치를 인용해 p99 개선 이유 설명 강화:

```markdown
**p99 레이턴시 개선 원인 — HikariCP 수치로 확인:**
- Phase 0: 재고 소진 후에도 active 커넥션 **[측정값]/10** 유지 → pending 대기 큐 포화
- Phase 1: 재고 소진 직후 active 커넥션 **[측정값]/10** 수준으로 급감 → pending=0
- 커넥션 반환 속도 차이가 대기 큐를 해소하여 p99 −XX% 달성
```

- [ ] **Step 4: 커밋**

```bash
git add docs/report/phase1-vs-phase0-load-test-comparison.md
git commit -m "docs: 부하 테스트 비교 문서 — 성공/실패 레이턴시 분리 및 HikariCP 지표 추가"
```
