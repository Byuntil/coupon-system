package cloud.coupon.domain.coupon.admin.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record CouponUpdateRequest(
        @Size(min = 1, max = 50, message = "쿠폰 이름은 1-50자 사이여야 합니다.")
        String name,

        // code는 수정 불가능하므로 제외

        @Min(value = 0, message = "쿠폰 총 수량은 0개 이상이어야 합니다.")
        Integer totalStock,

        // type은 수정 불가능하므로 제외

        @Min(value = 1, message = "할인 값은 1 이상이어야 합니다.")
        Integer discountValue,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime startTime,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime endTime,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime expireTime
) {
    public CouponUpdateRequest {
        if (startTime != null && endTime != null && expireTime != null) {
            validateTimeSequence(startTime, endTime, expireTime);
        }
    }

    private void validateTimeSequence(LocalDateTime startTime, LocalDateTime endTime, LocalDateTime expireTime) {
        if (startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("쿠폰 시작 시간은 종료 시간보다 이전이어야 합니다.");
        }
        if (endTime.isAfter(expireTime)) {
            throw new IllegalArgumentException("쿠폰 종료 시간은 만료 시간보다 이전이어야 합니다.");
        }
        if (startTime.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("쿠폰 시작 시간은 현재 시간 이후여야 합니다.");
        }
    }

}