package cloud.coupon.domain.coupon;


import cloud.coupon.domain.coupon.admin.dto.request.CouponCreateRequest;
import cloud.coupon.domain.coupon.admin.dto.request.CouponUpdateRequest;
import cloud.coupon.domain.coupon.entity.CouponType;
import java.time.LocalDateTime;

public class CouponFixture {
    public static CouponCreateRequest createRequest() {
        return new CouponCreateRequest(
                "테스트 쿠폰",
                "TEST-0001",
                10,
                CouponType.FIXED_AMOUNT,
                1000,
                LocalDateTime.of(2030, 1, 1, 0, 0),
                LocalDateTime.of(2034, 1, 31, 23, 59),
                LocalDateTime.of(3000, 1, 31, 23, 59)
        );
    }

    public static CouponUpdateRequest createUpdateRequest() {
        return new CouponUpdateRequest(
                "변경된 쿠폰",
                CouponType.FIXED_AMOUNT,
                1000,
                LocalDateTime.now(),
                LocalDateTime.now().plusDays(7),
                LocalDateTime.now().plusDays(30)
        );
    }

    public static CouponCreateRequest createRequestWithName(String name) {
        return new CouponCreateRequest(
                name,
                "TEST-0001",
                10,
                CouponType.FIXED_AMOUNT,
                1000,
                LocalDateTime.of(2030, 1, 1, 0, 0),
                LocalDateTime.of(2034, 1, 31, 23, 59),
                LocalDateTime.of(3000, 1, 31, 23, 59)
        );
    }
}
