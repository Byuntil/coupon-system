import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// === 환경 변수 ===
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS     = parseInt(__ENV.VUS    || '100', 10);
const STOCK   = parseInt(__ENV.STOCK  || '10000', 10);
const COUPON_CODE = __ENV.COUPON_CODE || 'PHASE0-DB-001';

// === 커스텀 메트릭 ===
const issuedCounter       = new Counter('coupons_issued');
const businessFailCounter = new Counter('coupons_business_failed');
const systemFailCounter   = new Counter('coupons_system_failed');

const issueSuccessRate = new Rate('issue_success_rate');
const systemErrorRate  = new Rate('system_error_rate');

const issueDurationAll     = new Trend('issue_duration_all',     true);
const issueDurationSuccess = new Trend('issue_duration_success', true);
const issueDurationFail    = new Trend('issue_duration_fail',    true);

// === 선착순 쿠폰 오픈 시나리오 ===
// 오픈 직후 폭발적 유입(3s) → 경쟁 구간(30s) → 램프다운(5s)
export const options = {
    scenarios: {
        phase0_db_only: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '3s',  target: VUS }, // 오픈 직후 폭발적 유입
                { duration: '30s', target: VUS }, // 경쟁 구간 (DB 포화 측정)
                { duration: '5s',  target: 0   }, // 램프다운
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        issue_duration_all:     ['p(99)<10000'],
        issue_duration_success: ['p(95)<500', 'p(99)<2000'],
        system_error_rate:      ['rate<0.05'],
    },
};

export function setup() {
    console.log(`=== Phase 0 DB-Only 부하 테스트 시작 ===`);
    console.log(`Strategy: db-only | VUs: ${VUS} | Stock: ${STOCK} | Coupon: ${COUPON_CODE}`);

    const payload = JSON.stringify({
        couponCode: COUPON_CODE,
        couponName: `Phase0 DB-Only 테스트 쿠폰 (VU=${VUS})`,
        totalStock: STOCK,
    });

    const res = http.post(`${BASE_URL}/api/v1/admin/load-test/setup`, payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'setup_coupon' },
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

    const params = {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'issue_coupon' },
        timeout: '10s',
    };

    const res = http.post(`${BASE_URL}/api/v1/coupons/issue`, payload, params);
    issueDurationAll.add(res.timings.duration);

    if (res.status === 201) {
        issuedCounter.add(1);
        issueSuccessRate.add(true);
        systemErrorRate.add(false);
        issueDurationSuccess.add(res.timings.duration);
        return;
    }

    if (res.status === 400 || res.status === 409) {
        businessFailCounter.add(1);
        issueSuccessRate.add(false);
        systemErrorRate.add(false);
        issueDurationFail.add(res.timings.duration);
        return;
    }

    systemFailCounter.add(1);
    issueSuccessRate.add(false);
    systemErrorRate.add(true);

    if (__ITER < 5) {
        console.warn(`[VU=${__VU}] Unexpected status=${res.status}, body=${res.body}`);
    }
}

export function teardown(data) {
    sleep(3);

    const verifyRes = http.post(
        `${BASE_URL}/api/v1/admin/load-test/verify`,
        JSON.stringify({ couponCode: data.couponCode }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    if (verifyRes.status === 200) {
        const result = JSON.parse(verifyRes.body);
        console.log(`=== 정합성 검증 ===`);
        console.log(`VUs: ${data.vus} | Stock: ${data.stock}`);
        console.log(`Total Stock:  ${result.totalStock}`);
        console.log(`Remain Stock: ${result.remainStock}`);
        console.log(`Issued Count: ${result.issuedCount}`);
        console.log(`Consistent:   ${result.consistent}`);

        check(result, {
            'issued count must not exceed stock': (r) => r.issuedCount <= r.totalStock,
            'stock consistency must be true':     (r) => r.consistent === true,
        });

        if (!result.consistent) {
            console.error(
                `정합성 불일치: ${result.totalStock} - ${result.remainStock} ≠ ${result.issuedCount}`
            );
        }
    } else {
        console.error(`Verify failed: ${verifyRes.status} ${verifyRes.body}`);
    }

    http.post(
        `${BASE_URL}/api/v1/admin/load-test/teardown`,
        JSON.stringify({ couponCode: data.couponCode }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    console.log(`=== Phase 0 부하 테스트 종료 ===`);
}
