package cloud.coupon.domain.coupon.controller;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.dto.request.CouponUseRequest;
import cloud.coupon.domain.coupon.dto.response.CouponIssueResult;
import cloud.coupon.domain.coupon.dto.response.CouponUseResponse;
import cloud.coupon.domain.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Deprecated(since = "v2", forRemoval = false)
@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class CouponController {
    private final CouponService couponService;

    @PostMapping("/issue")
    public ResponseEntity<CouponIssueResult> issueCoupon(@RequestBody CouponIssueRequest request) {
        // 서버 수신 시점 기록 — 순서 역전 측정용 (System.nanoTime: 단조 시계, JVM 내 비교만 유효)
        CouponIssueRequest timestamped = new CouponIssueRequest(
                request.code(), request.userId(), request.requestIp(), System.nanoTime());
        CouponIssueResult result = couponService.issueCoupon(timestamped);
        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @PostMapping("/use")
    public ResponseEntity<CouponUseResponse> useCoupon(@RequestBody CouponUseRequest request) {
        CouponUseResponse couponUseResponse = couponService.useCoupon(request.userId(), request.issueCode());
        return ResponseEntity.status(HttpStatus.OK).body(couponUseResponse);
    }
}
