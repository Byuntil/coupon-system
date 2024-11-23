package cloud.coupon.domain.coupon.admin.dto.request;

import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponType;
import java.time.LocalDateTime;

public record CouponCreateRequest(String name, String code, Integer totalStock, CouponType couponType,
                                  Integer discountValue, LocalDateTime startTime, LocalDateTime endTime,
                                  LocalDateTime expireTime) {
    public static CouponCreateRequest from(Coupon coupon) {
        return new CouponCreateRequest(
                coupon.getName(),
                coupon.getCode(),
                coupon.getTotalStock(),
                coupon.getType(),
                coupon.getDiscountValue(),
                coupon.getStartTime(),
                coupon.getEndTime(),
                coupon.getExpireTime());
    }
}