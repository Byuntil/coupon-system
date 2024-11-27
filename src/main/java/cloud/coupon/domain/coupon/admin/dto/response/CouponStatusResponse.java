package cloud.coupon.domain.coupon.admin.dto.response;

import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponStatus;
import cloud.coupon.domain.coupon.entity.CouponType;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

public record CouponStatusResponse(
        String name,            // 쿠폰 이름
        String code,           // 쿠폰 코드
        CouponType type,       // 쿠폰 타입(정액/정률)
        Integer discountValue, // 할인 값
        Integer totalStock,   // 총 재고
        Integer remainStock,   // 남은 재고
        Double issueRate,     // 발급률 (%)
        CouponStatus status,   // 쿠폰 상태
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime startTime,    // 발급 시작 시간
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime endTime,      // 발급 종료 시간
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime expireTime   // 쿠폰 만료 시간
) {
    public static CouponStatusResponse from(Coupon coupon) {
        double issueRate = calculateIssueRate(coupon.getTotalStock(), coupon.getRemainStock());

        return new CouponStatusResponse(
                coupon.getName(),
                coupon.getCode(),
                coupon.getType(),
                coupon.getDiscountValue(),
                coupon.getTotalStock(),
                coupon.getRemainStock(),
                issueRate,
                coupon.getStatus(),
                coupon.getStartTime(),
                coupon.getEndTime(),
                coupon.getExpireTime());
    }

    private static double calculateIssueRate(int totalStock, int remainStock) {
        if (totalStock == 0) {
            return 0.0;
        }
        return Math.round(((totalStock - remainStock) / (double) totalStock * 100) * 100) / 100.0;
    }
}