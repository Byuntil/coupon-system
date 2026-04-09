package cloud.coupon.domain.coupon;


import cloud.coupon.domain.coupon.admin.dto.request.CouponCreateRequest;
import cloud.coupon.domain.coupon.admin.dto.request.CouponUpdateRequest;
import cloud.coupon.domain.coupon.entity.CouponType;
import java.time.LocalDateTime;

public class CouponFixture {
    public static CouponCreateRequest createRequest() {
        LocalDateTime baseTime = LocalDateTime.of(2030, 1, 1, 0, 0);
        return new CouponCreateRequest(
                "테스트 쿠폰",
                "TEST-0001",
                10,
                CouponType.FIXED_AMOUNT,
                1000,
                baseTime,
                baseTime.plusYears(4).minusDays(1),
                baseTime.plusYears(970).minusDays(1)
        );
    }

    public static CouponUpdateRequest createUpdateRequest() {
        LocalDateTime baseTime = LocalDateTime.now().plusDays(1);
        return new CouponUpdateRequest(
                "변경된 쿠폰",
                CouponType.FIXED_AMOUNT,
                1000,
                baseTime,
                baseTime.plusDays(7),
                baseTime.plusDays(30)
        );
    }

    public static CouponCreateRequest createRequestWithName(String name) {
        LocalDateTime baseTime = LocalDateTime.of(2030, 1, 1, 0, 0);
        return new CouponCreateRequest(
                name,
                "TEST-0001",
                10,
                CouponType.FIXED_AMOUNT,
                1000,
                baseTime,
                baseTime.plusYears(4).minusDays(1),
                baseTime.plusYears(970).minusDays(1)
        );
    }
}
