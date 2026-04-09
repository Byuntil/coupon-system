package cloud.coupon.domain.coupon.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

public record CouponUseResponse(
        boolean success,
        Integer discountValue,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime usedAt
) {
}
