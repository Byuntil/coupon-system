package cloud.coupon.domain.coupon.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CouponIssueRequest(
        @NotBlank String code,
        @NotNull Long userId,
        String requestIp,
        Long serverReceivedAtNanos  // 서버 수신 시점 (System.nanoTime), 클라이언트에서 설정 불가
) {
    public CouponIssueRequest(String code, Long userId, String requestIp) {
        this(code, userId, requestIp, null);
    }
}
