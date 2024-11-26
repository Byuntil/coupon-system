package cloud.coupon.domain.coupon.admin.controller;

import cloud.coupon.domain.coupon.admin.dto.request.CouponCreateRequest;
import cloud.coupon.domain.coupon.admin.dto.request.CouponUpdateRequest;
import cloud.coupon.domain.coupon.admin.dto.response.CouponResponse;
import cloud.coupon.domain.coupon.admin.dto.response.CouponStatusResponse;
import cloud.coupon.domain.coupon.admin.service.CouponAdminService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/coupons")
public class CouponAdminController {

    private final CouponAdminService couponAdminService;

    @PostMapping
    public ResponseEntity<CouponResponse> createCoupon(
            @Validated @RequestBody CouponCreateRequest request
    ) {
        CouponResponse response = couponAdminService.createCoupon(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{couponId}")
    public ResponseEntity<CouponResponse> updateCoupon(
            @PathVariable Long couponId,
            @Validated @RequestBody CouponUpdateRequest request
    ) {
        CouponResponse response = couponAdminService.updateCoupon(couponId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{couponId}")
    public ResponseEntity<Void> deleteCoupon(@PathVariable Long couponId) {
        couponAdminService.deleteCoupon(couponId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{couponId}/disable")
    public ResponseEntity<Void> disableCouponIssue(@PathVariable Long couponId) {
        couponAdminService.disableCoupon(couponId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/statistics")
    public ResponseEntity<List<CouponStatusResponse>> getCouponStatistics() {
        List<CouponStatusResponse> statistics = couponAdminService.getCouponStatistics();
        return ResponseEntity.ok(statistics);
    }

    @GetMapping("/status/{code}")
    public ResponseEntity<CouponStatusResponse> getCouponStatus(@PathVariable String code) {
        CouponStatusResponse status = couponAdminService.getCouponStatus(code);
        return ResponseEntity.ok(status);
    }
}