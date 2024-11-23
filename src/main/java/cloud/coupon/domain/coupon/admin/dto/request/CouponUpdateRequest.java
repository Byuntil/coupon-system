package cloud.coupon.domain.coupon.admin.dto.request;

import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponType;
import java.time.LocalDateTime;

public record CouponUpdateRequest(String name, String code, Integer totalStock, CouponType couponType,
                                  Integer discountValue, LocalDateTime startTime, LocalDateTime endTime,
                                  LocalDateTime expireTime) {
    public static CouponUpdateRequest from(Coupon coupon) {
        return new CouponUpdateRequest(
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
