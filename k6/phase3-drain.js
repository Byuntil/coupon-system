/**
 * P3-2: Phase 3 Drain 성능 측정
 *
 * 목적: backlog 형성 후 단일 consumer의 처리 성능(drain time)을 측정한다.
 *
 * 방식:
 *   1. 모든 VU가 동시에 POST /api/v2/coupons/issue (burst)
 *   2. 각 VU가 GET /api/v2/coupons/status/{ticketId}를 polling하여 완료 확인
 *   3. admission → completion E2E 시간을 기록
 *
 * 설정:
 *   - per-vu-iterations executor: 각 VU가 정확히 1회 발급 + polling 수행
 *   - 재고를 VU 수보다 크게 잡아 모든 요청이 accepted 되도록 함
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// === 환경 변수 ===
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '1000', 10);
const STOCK = parseInt(__ENV.STOCK || '5000', 10);
const COUPON_CODE = __ENV.COUPON_CODE || 'P3-DRAIN-001';
const POLL_INTERVAL_MS = parseInt(__ENV.POLL_INTERVAL_MS || '500', 10);
const MAX_WAIT_MS = parseInt(__ENV.MAX_WAIT_MS || '60000', 10);

// === 커스텀 메트릭 ===
const acceptedCounter = new Counter('p3_drain_accepted');
const completedCounter = new Counter('p3_drain_completed');
const failedCounter = new Counter('p3_drain_failed');
const timeoutCounter = new Counter('p3_drain_timeout');

const completionRate = new Rate('p3_drain_completion_rate');

const admissionDuration = new Trend('p3_drain_admission_ms', true);
const e2eDuration = new Trend('p3_drain_e2e_ms', true);
const pollCount = new Trend('p3_drain_poll_count', true);

// === 시나리오 ===
export const options = {
    scenarios: {
        p3_drain: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '180s',
        },
    },
    thresholds: {
        p3_drain_completion_rate: ['rate>0.95'],
        p3_drain_e2e_ms: ['p(95)<30000'],
    },
};

export function setup() {
    console.log('=== P3-2: Phase 3 Drain 성능 측정 시작 ===');
    console.log(`VUs: ${VUS} | Stock: ${STOCK} | Poll: ${POLL_INTERVAL_MS}ms | MaxWait: ${MAX_WAIT_MS}ms`);

    const payload = JSON.stringify({
        couponCode: COUPON_CODE,
        couponName: `P3-2 Drain 테스트 (VU=${VUS})`,
        totalStock: STOCK,
    });

    const res = http.post(`${BASE_URL}/api/v1/admin/load-test/setup-phase3`, payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'setup' },
    });

    check(res, { 'setup status is 200': (r) => r.status === 200 });
    if (res.status !== 200) {
        throw new Error(`Setup failed: ${res.status} ${res.body}`);
    }

    return { couponCode: COUPON_CODE, stock: STOCK, vus: VUS };
}

export default function (data) {
    const userId = __VU;

    const issuePayload = JSON.stringify({
        code: data.couponCode,
        userId,
        requestIp: '127.0.0.1',
    });

    const issueStart = Date.now();
    const issueRes = http.post(`${BASE_URL}/api/v2/coupons/issue`, issuePayload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'drain_issue' },
        timeout: '10s',
    });

    admissionDuration.add(issueRes.timings.duration);

    if (issueRes.status !== 202) {
        completionRate.add(false);
        return;
    }

    acceptedCounter.add(1);

    let body;
    try {
        body = JSON.parse(issueRes.body);
    } catch (error) {
        completionRate.add(false);
        return;
    }

    const ticketId = body.ticketId;
    if (!ticketId) {
        completionRate.add(false);
        return;
    }

    let status = 'PENDING';
    let polls = 0;

    while (status === 'PENDING' && (Date.now() - issueStart) < MAX_WAIT_MS) {
        sleep(POLL_INTERVAL_MS / 1000);
        polls++;

        const statusRes = http.get(`${BASE_URL}/api/v2/coupons/status/${ticketId}`, {
            tags: { name: 'drain_poll' },
            timeout: '5s',
        });

        if (statusRes.status === 200) {
            try {
                status = JSON.parse(statusRes.body).status;
            } catch (error) {
                // JSON 파싱 실패 → 다음 poll에서 재시도
            }
        }
    }

    const e2eTime = Date.now() - issueStart;
    e2eDuration.add(e2eTime);
    pollCount.add(polls);

    if (status === 'COMPLETED') {
        completedCounter.add(1);
        completionRate.add(true);
    } else if (status === 'FAILED') {
        failedCounter.add(1);
        completionRate.add(false);
    } else {
        timeoutCounter.add(1);
        completionRate.add(false);
    }
}

export function teardown(data) {
    sleep(10);

    const verifyRes = http.post(
        `${BASE_URL}/api/v1/admin/load-test/verify-phase3`,
        JSON.stringify({ couponCode: data.couponCode }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    if (verifyRes.status === 200) {
        const result = JSON.parse(verifyRes.body);
        console.log('=== P3-2 검증 결과 ===');
        console.log(
            `DB: totalStock=${result.totalStock} | remainStock=${result.dbRemainStock} | issued=${result.dbIssuedCount} | consistent=${result.dbConsistent}`
        );
        console.log(
            `Redis: stock=${result.redisRemainStock} | inflight=${result.inflightCount} | issued=${result.issuedSetCount}`
        );
        console.log(`Stream: length=${result.streamLength} | DLQ=${result.dlqLength}`);
        console.log(`All processed: ${result.allProcessed}`);

        check(result, {
            'DB consistency': (r) => r.dbConsistent === true,
            'no inflight remaining': (r) => r.inflightCount === 0,
            'no DLQ entries': (r) => r.dlqLength === 0,
        });
    } else {
        console.error(`Verify failed: ${verifyRes.status} ${verifyRes.body}`);
    }

    http.post(
        `${BASE_URL}/api/v1/admin/load-test/teardown-phase3`,
        JSON.stringify({ couponCode: data.couponCode }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    console.log('=== P3-2 테스트 종료 ===');
}
