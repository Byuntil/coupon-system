package cloud.coupon.domain.coupon.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CouponIssueRequest(
        @NotBlank String code,
        @NotNull Long userId,
        String requestIp
) {
}
