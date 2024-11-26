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

@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class CouponController {
    private final CouponService couponService;

    @PostMapping("/issue")
    public ResponseEntity<CouponIssueResult> issueCoupon(@RequestBody CouponIssueRequest request) {
        CouponIssueResult result = couponService.issueCoupon(request);
        if (result.isSuccess()) {
            return new ResponseEntity<>(result, HttpStatus.CREATED);
        }
        return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
    }

    @PostMapping("/use")
    public ResponseEntity<CouponUseResponse> useCoupon(@RequestBody CouponUseRequest request) {
        CouponUseResponse couponUseResponse = couponService.useCoupon(request.userId(), request.issueCode());
        return new ResponseEntity<>(couponUseResponse, HttpStatus.OK);
    }
}
