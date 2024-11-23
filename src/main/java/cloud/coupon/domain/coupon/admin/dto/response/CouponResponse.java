package cloud.coupon.domain.coupon.admin.dto.response;

import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponStatus;
import cloud.coupon.domain.coupon.entity.CouponType;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

public record CouponResponse(
        Long id,
        String name,
        String code,
        Integer totalStock,
        Integer remainStock,
        Integer usedCount,
        CouponType type,
        Integer discountValue,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime startTime,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime endTime,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime expireTime,
        CouponStatus status,
        boolean isDeleted
) {
    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
                coupon.getId(),
                coupon.getName(),
                coupon.getCode(),
                coupon.getTotalStock(),
                coupon.getRemainStock(),
                coupon.getUsedCount(),
                coupon.getType(),
                coupon.getDiscountValue(),
                coupon.getStartTime(),
                coupon.getEndTime(),
                coupon.getExpireTime(),
                coupon.getStatus(),
                coupon.isDeleted()
        );
    }
}
