/**
 * P3-3: Phase 3 선착순/정합성 검증
 *
 * 목적:
 *   - 정확히 STOCK건만 accepted 되는가
 *   - 최종 DB 발급 건수도 정확히 STOCK건인가
 *   - 중복 발급이 없는가
 *
 * 설정:
 *   - 재고 100, 요청 1000 (default)
 *   - 각 VU = 1명의 유저 (userId = __VU)
 *   - 모든 VU가 정확히 1회 요청 후 polling으로 최종 상태 확인
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// === 환경 변수 ===
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '1000', 10);
const STOCK = parseInt(__ENV.STOCK || '100', 10);
const COUPON_CODE = __ENV.COUPON_CODE || 'P3-FAIR-001';
const POLL_INTERVAL_MS = parseInt(__ENV.POLL_INTERVAL_MS || '500', 10);
const MAX_WAIT_MS = parseInt(__ENV.MAX_WAIT_MS || '30000', 10);

// === 커스텀 메트릭 ===
const acceptedCounter = new Counter('p3_fair_accepted');
const rejectedCounter = new Counter('p3_fair_rejected');
const completedCounter = new Counter('p3_fair_completed');
const failedCounter = new Counter('p3_fair_failed');
const timeoutCounter = new Counter('p3_fair_timeout');

const e2eDuration = new Trend('p3_fair_e2e_ms', true);

// === 시나리오 ===
export const options = {
    scenarios: {
        p3_fairness: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '120s',
        },
    },
};

export function setup() {
    console.log('=== P3-3: Phase 3 선착순/정합성 검증 시작 ===');
    console.log(`VUs: ${VUS} | Stock: ${STOCK} | Coupon: ${COUPON_CODE}`);
    console.log(`예상: ${STOCK}명 accepted, ${VUS - STOCK}명 rejected`);

    const payload = JSON.stringify({
        couponCode: COUPON_CODE,
        couponName: 'P3-3 정합성 테스트',
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
        tags: { name: 'fairness_issue' },
        timeout: '10s',
    });

    if (issueRes.status === 202) {
        acceptedCounter.add(1);

        let body;
        try {
            body = JSON.parse(issueRes.body);
        } catch (error) {
            return;
        }

        const ticketId = body.ticketId;
        if (!ticketId) {
            return;
        }

        let status = 'PENDING';
        while (status === 'PENDING' && (Date.now() - issueStart) < MAX_WAIT_MS) {
            sleep(POLL_INTERVAL_MS / 1000);

            const statusRes = http.get(`${BASE_URL}/api/v2/coupons/status/${ticketId}`, {
                tags: { name: 'fairness_poll' },
                timeout: '5s',
            });

            if (statusRes.status === 200) {
                try {
                    status = JSON.parse(statusRes.body).status;
                } catch (error) {
                    // 파싱 실패 → 재시도
                }
            }
        }

        const e2eTime = Date.now() - issueStart;
        e2eDuration.add(e2eTime);

        if (status === 'COMPLETED') {
            completedCounter.add(1);
        } else if (status === 'FAILED') {
            failedCounter.add(1);
        } else {
            timeoutCounter.add(1);
        }

        return;
    }

    if (issueRes.status === 400 || issueRes.status === 409) {
        rejectedCounter.add(1);
        return;
    }

    if (__ITER === 0) {
        console.warn(`[VU=${__VU}] Unexpected status=${issueRes.status}, body=${issueRes.body}`);
    }
}

export function teardown(data) {
    console.log('Consumer drain 대기 중 (15초)...');
    sleep(15);

    const verifyRes = http.post(
        `${BASE_URL}/api/v1/admin/load-test/verify-phase3`,
        JSON.stringify({ couponCode: data.couponCode }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    if (verifyRes.status === 200) {
        const result = JSON.parse(verifyRes.body);

        console.log('\n=== P3-3 정합성 검증 ===');
        console.log(`DB: totalStock=${result.totalStock} | remainStock=${result.dbRemainStock} | issued=${result.dbIssuedCount}`);
        console.log(`Redis: stock=${result.redisRemainStock} | inflight=${result.inflightCount} | issued=${result.issuedSetCount}`);
        console.log(`Stream: length=${result.streamLength} | DLQ=${result.dlqLength}`);

        check(result, {
            'DB issued count must equal stock': (r) => r.dbIssuedCount === data.stock,
            'DB consistency': (r) => r.dbConsistent === true,
            'DB remain stock must be 0': (r) => r.dbRemainStock === 0,
            'no inflight remaining': (r) => r.inflightCount === 0,
            'Redis issued set equals stock': (r) => r.issuedSetCount === data.stock,
            'no DLQ entries': (r) => r.dlqLength === 0,
        });

        const passed =
            result.dbIssuedCount === data.stock &&
            result.dbConsistent &&
            result.inflightCount === 0;
        console.log(`\n정합성 결과: ${passed ? 'PASS' : 'FAIL'}`);

        if (result.dbIssuedCount !== data.stock) {
            console.error(`발급 건수 불일치: expected=${data.stock}, actual=${result.dbIssuedCount}`);
        }
    } else {
        console.error(`Verify failed: ${verifyRes.status} ${verifyRes.body}`);
    }

    http.post(
        `${BASE_URL}/api/v1/admin/load-test/teardown-phase3`,
        JSON.stringify({ couponCode: data.couponCode }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    console.log('=== P3-3 테스트 종료 ===');
}
