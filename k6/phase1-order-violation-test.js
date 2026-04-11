/**
 * 순서 역전(Ordering Violation) 측정 스크립트
 *
 * 목적: Phase 1 Redis 전략에서 서버 수신 순서와 발급 결과가 일치하지 않는 케이스를 측정
 *
 * 측정 방법:
 * - VU 수 = 500, 재고 = 250 (절반만 발급 가능)
 * - 각 VU의 userId = __VU (1~500)
 * - 모든 VU가 동시에 요청 → 서버 수신 순서는 랜덤
 * - 테스트 후 DB 쿼리로 순서 역전 확인
 */

import http from 'k6/http';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8080';
const COUPON_CODE = __ENV.COUPON_CODE || 'ORDER-TEST-001';
const VUS         = parseInt(__ENV.VUS   || '1000', 10);
const STOCK       = parseInt(__ENV.STOCK || '500',  10);

const successCounter = new Counter('order_test_success');
const failCounter    = new Counter('order_test_fail');
const successRate    = new Rate('order_test_success_rate');

export const options = {
    scenarios: {
        order_violation: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '1s',  target: VUS }, // 빠르게 동시 진입 → 경쟁 극대화
                { duration: '10s', target: VUS },
                { duration: '2s',  target: 0   },
            ],
            gracefulRampDown: '5s',
        },
    },
    // 이 테스트는 성능 측정이 아니므로 threshold 없음
};

export function setup() {
    console.log(`=== 순서 역전 측정 테스트 시작 ===`);
    console.log(`VUs: ${VUS} | Stock: ${STOCK} | Coupon: ${COUPON_CODE}`);
    console.log(`예상: ${STOCK}명 성공, ${VUS - STOCK}명 실패`);

    const res = http.post(`${BASE_URL}/api/v1/admin/load-test/setup`,
        JSON.stringify({
            couponCode: COUPON_CODE,
            couponName: `순서 역전 측정 쿠폰`,
            totalStock: STOCK,
        }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    if (res.status !== 200) {
        throw new Error(`Setup 실패: ${res.status} ${res.body}`);
    }

    return { couponCode: COUPON_CODE };
}

export default function (data) {
    // __VU = 1~500 (VU 번호 = userId로 사용)
    // 순번이 낮을수록 "먼저 요청하는" 의도이지만 실제 서버 도달 순서는 랜덤
    const userId = __VU;

    const res = http.post(`${BASE_URL}/api/v1/coupons/issue`,
        JSON.stringify({ code: data.couponCode, userId, requestIp: '127.0.0.1' }),
        { headers: { 'Content-Type': 'application/json' }, timeout: '10s' }
    );

    const ok = res.status === 201;
    successRate.add(ok);

    if (ok) {
        successCounter.add(1);
    } else {
        failCounter.add(1);
    }
}

export function teardown(data) {
    const verifyRes = http.post(
        `${BASE_URL}/api/v1/admin/load-test/verify`,
        JSON.stringify({ couponCode: data.couponCode }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    if (verifyRes.status === 200) {
        const r = JSON.parse(verifyRes.body);
        console.log(`\n=== 정합성 검증 ===`);
        console.log(`발급 성공: ${r.issuedCount} / 총 재고: ${r.totalStock}`);
        console.log(`정합성: ${r.consistent}`);
    }

    // 순서 역전 분석
    const analysisRes = http.post(
        `${BASE_URL}/api/v1/admin/load-test/analyze-ordering`,
        JSON.stringify({ couponCode: data.couponCode }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    if (analysisRes.status === 200) {
        const a = JSON.parse(analysisRes.body);
        console.log(`\n=== 순서 역전 분석 결과 ===`);
        console.log(`성공: ${a.successCount}건 | 실패: ${a.failCount}건`);
        console.log(`순서 역전: ${a.orderingViolations}건 (실패 중 ${a.violationRate})`);
        if (a.orderingViolations > 0) {
            console.log(`선착순 미보장 확인: 먼저 도착했지만 쿠폰 못 받은 케이스 존재`);
        } else {
            console.log(`순서 역전 없음 (또는 측정 불가)`);
        }
    }

    http.post(
        `${BASE_URL}/api/v1/admin/load-test/teardown`,
        JSON.stringify({ couponCode: data.couponCode }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    console.log(`=== 테스트 종료 ===`);
}
