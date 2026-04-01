import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// === 환경 변수 ===
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '100', 10);
const STOCK = parseInt(__ENV.STOCK || '100', 10);
const COUPON_CODE = __ENV.COUPON_CODE || 'LOAD-TEST-001';

// === 커스텀 메트릭 ===
const issuedCounter = new Counter('coupons_issued');
const businessFailCounter = new Counter('coupons_business_failed'); // 재고 소진 등 정상 실패
const systemFailCounter = new Counter('coupons_system_failed');     // 5xx, timeout 등 비정상 실패

const issueSuccessRate = new Rate('issue_success_rate');
const systemErrorRate = new Rate('system_error_rate');

const issueDurationAll = new Trend('issue_duration_all', true);
const issueDurationSuccess = new Trend('issue_duration_success', true);

export const options = {
    scenarios: {
        s1_single_coupon: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: VUS },
                { duration: '20s', target: VUS },
                { duration: '5s', target: 0 },
            ],
            gracefulRampDown: '30s',
        },
    },
    thresholds: {
        issue_duration_all: ['p(99)<5000'],
        issue_duration_success: ['p(95)<300', 'p(99)<1000'],
        system_error_rate: ['rate<0.01'], // 진짜 서버 장애만 1% 미만
    },
};

export function setup() {
    console.log(`=== 부하 테스트 시작 ===`);
    console.log(`VUs: ${VUS}, Stock: ${STOCK}, Coupon: ${COUPON_CODE}`);

    const setupPayload = JSON.stringify({
        couponCode: COUPON_CODE,
        couponName: '부하테스트 쿠폰',
        totalStock: STOCK,
    });

    const res = http.post(`${BASE_URL}/api/v1/admin/load-test/setup`, setupPayload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'setup_coupon' },
    });

    check(res, {
        'setup status is 200': (r) => r.status === 200,
    });

    if (res.status !== 200) {
        throw new Error(`Setup failed: ${res.status} ${res.body}`);
    }

    return { couponCode: COUPON_CODE, stock: STOCK };
}

export default function (data) {
    const userId = __VU * 100000 + __ITER;

    const payload = JSON.stringify({
        code: data.couponCode,
        userId,
        requestIp: '127.0.0.1',
    });

    const params = {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'issue_coupon' },
        timeout: '5s',
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
        // 재고 소진 / 중복 발급 / 비즈니스 규칙 위반 등
        businessFailCounter.add(1);
        issueSuccessRate.add(false);
        systemErrorRate.add(false);
        return;
    }

    // 네트워크 에러 / 5xx / 예상 못 한 상태코드
    systemFailCounter.add(1);
    issueSuccessRate.add(false);
    systemErrorRate.add(true);

    if (__ITER < 3) {
        console.warn(`Unexpected status=${res.status}, body=${res.body}`);
    }
}

export function teardown(data) {
    sleep(2);

    const verifyPayload = JSON.stringify({ couponCode: data.couponCode });
    const verifyRes = http.post(`${BASE_URL}/api/v1/admin/load-test/verify`, verifyPayload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'verify_coupon' },
    });

    if (verifyRes.status === 200) {
        const result = JSON.parse(verifyRes.body);

        console.log(`=== 정합성 검증 ===`);
        console.log(`Total Stock: ${result.totalStock}`);
        console.log(`Remain Stock: ${result.remainStock}`);
        console.log(`Issued Count: ${result.issuedCount}`);
        console.log(`Consistent: ${result.consistent}`);

        check(result, {
            'issued count must not exceed stock': (r) => r.issuedCount <= r.totalStock,
            'stock consistency must be true': (r) => r.consistent === true,
        });

        if (!result.consistent) {
            console.error(
                `정합성 불일치: totalStock(${result.totalStock}) - remainStock(${result.remainStock}) != issuedCount(${result.issuedCount})`
            );
        }
    } else {
        console.error(`Verify failed: ${verifyRes.status} ${verifyRes.body}`);
    }

    const teardownPayload = JSON.stringify({ couponCode: data.couponCode });
    http.post(`${BASE_URL}/api/v1/admin/load-test/teardown`, teardownPayload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'teardown_coupon' },
    });

    console.log(`=== 부하 테스트 종료 ===`);
}