package cloud.coupon.domain.coupon.dto.request;

public record CouponUseRequest(
        Long userId,
        String issueCode
) {
}
