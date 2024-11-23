package cloud.coupon.domain.coupon.dto.request;

public record CouponIssueRequest(String code, Long userId, String requestIp) {
}
