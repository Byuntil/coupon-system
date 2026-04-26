/**
 * P3-1: Phase 3 접수 처리량 측정
 *
 * 목적: POST /api/v2/coupons/issue 의 admission throughput 측정.
 * Redis Lua(중복확인 + 재고차감 + XADD) 경로의 ceiling을 확인한다.
 *
 * 설정:
 *   - 재고를 충분히 크게 잡아 OUT_OF_STOCK이 측정 구간에서 발생하지 않게 한다.
 *   - Consumer 처리 지연과 무관하게 순수 admission 성능만 측정한다.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// === 환경 변수 ===
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '1000', 10);
const STOCK = parseInt(__ENV.STOCK || '100000', 10);
const COUPON_CODE = __ENV.COUPON_CODE || 'P3-ADMISSION-001';
const DURATION = __ENV.DURATION || '30s';
const RAMP_UP = __ENV.RAMP_UP || '10s';
const VERIFY_POLL_INTERVAL_MS = parseInt(__ENV.VERIFY_POLL_INTERVAL_MS || '1000', 10);
const VERIFY_MAX_WAIT_MS = parseInt(__ENV.VERIFY_MAX_WAIT_MS || '15000', 10);
const TEARDOWN_MODE = __ENV.TEARDOWN_MODE || 'cleanup';

// === 커스텀 메트릭 ===
const acceptedCounter = new Counter('p3_accepted');
const rejectedCounter = new Counter('p3_rejected');
const systemFailCounter = new Counter('p3_system_failed');

const acceptedRate = new Rate('p3_accepted_rate');
const systemErrorRate = new Rate('p3_system_error_rate');

const admissionDurationAll = new Trend('p3_admission_duration_all', true);
const admissionDurationAccept = new Trend('p3_admission_duration_accept', true);
const admissionDurationReject = new Trend('p3_admission_duration_reject', true);

// === 시나리오 ===
export const options = {
    scenarios: {
        p3_admission: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: RAMP_UP, target: VUS },
                { duration: DURATION, target: VUS },
                { duration: '5s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        p3_admission_duration_accept: ['p(95)<200', 'p(99)<500'],
        p3_system_error_rate: ['rate<0.01'],
    },
};

export function setup() {
    console.log('=== P3-1: Phase 3 접수 처리량 측정 시작 ===');
    console.log(`VUs: ${VUS} | Stock: ${STOCK} | Duration: ${DURATION} | Coupon: ${COUPON_CODE}`);

    const payload = JSON.stringify({
        couponCode: COUPON_CODE,
        couponName: `P3-1 접수 처리량 테스트 (VU=${VUS})`,
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
    const userId = __VU * 1000000 + __ITER;

    const payload = JSON.stringify({
        code: data.couponCode,
        userId,
        requestIp: '127.0.0.1',
    });

    const res = http.post(`${BASE_URL}/api/v2/coupons/issue`, payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'admission' },
        timeout: '10s',
    });

    admissionDurationAll.add(res.timings.duration);

    if (res.status === 202) {
        acceptedCounter.add(1);
        acceptedRate.add(true);
        systemErrorRate.add(false);
        admissionDurationAccept.add(res.timings.duration);
        return;
    }

    if (res.status === 400 || res.status === 409) {
        rejectedCounter.add(1);
        acceptedRate.add(false);
        systemErrorRate.add(false);
        admissionDurationReject.add(res.timings.duration);
        sleep(0.05);
        return;
    }

    systemFailCounter.add(1);
    acceptedRate.add(false);
    systemErrorRate.add(true);

    if (__ITER < 5) {
        console.warn(`[VU=${__VU}] Unexpected status=${res.status}, body=${res.body}`);
    }
}

export function teardown(data) {
    let verifyRes;
    const startedAt = Date.now();

    while (Date.now() - startedAt < VERIFY_MAX_WAIT_MS) {
        verifyRes = http.post(
            `${BASE_URL}/api/v1/admin/load-test/verify-phase3`,
            JSON.stringify({ couponCode: data.couponCode }),
            { headers: { 'Content-Type': 'application/json' } }
        );

        if (verifyRes.status !== 200) {
            break;
        }

        const result = JSON.parse(verifyRes.body);
        if (result.allProcessed === true) {
            break;
        }

        sleep(VERIFY_POLL_INTERVAL_MS / 1000);
    }

    if (verifyRes && verifyRes.status === 200) {
        const result = JSON.parse(verifyRes.body);
        console.log('=== P3-1 검증 결과 ===');
        console.log(
            `DB: totalStock=${result.totalStock} | remainStock=${result.dbRemainStock} | issued=${result.dbIssuedCount} | consistent=${result.dbConsistent}`
        );
        console.log(
            `Redis: stock=${result.redisRemainStock} | inflight=${result.inflightCount} | issued=${result.issuedSetCount}`
        );
        console.log(`Stream: length=${result.streamLength} | DLQ=${result.dlqLength}`);
        console.log(`All processed: ${result.allProcessed}`);
    } else if (verifyRes) {
        console.error(`Verify failed: ${verifyRes.status} ${verifyRes.body}`);
    }

    if (TEARDOWN_MODE === 'verify-only') {
        console.log('Teardown mode is verify-only. External runner must clean up Phase 3 state.');
        console.log('=== P3-1 테스트 종료 ===');
        return;
    }

    const teardownRes = http.post(
        `${BASE_URL}/api/v1/admin/load-test/teardown-phase3`,
        JSON.stringify({ couponCode: data.couponCode }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    if (teardownRes.status !== 200) {
        console.error(`Teardown failed: ${teardownRes.status} ${teardownRes.body}`);
    }

    console.log('=== P3-1 테스트 종료 ===');
}
