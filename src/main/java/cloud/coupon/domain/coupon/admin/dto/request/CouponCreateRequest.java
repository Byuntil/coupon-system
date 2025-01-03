package cloud.coupon.domain.coupon.admin.dto.request;

import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponType;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;


public record CouponCreateRequest(
        @NotBlank(message = "쿠폰 이름은 필수입니다.")
        @Size(min = 1, max = 50, message = "쿠폰 이름은 1-50자 사이여야 합니다.")
        String name,

        @NotBlank(message = "쿠폰 코드는 필수입니다.")
        @Size(min = 1, max = 20, message = "쿠폰 코드는 1-20자 사이여야 합니다.")
        String code,

        @NotNull(message = "쿠폰 총 수량은 필수입니다.")
        @Min(value = 1, message = "쿠폰 총 수량은 1개 이상이어야 합니다.")
        Integer totalStock,

        @NotNull(message = "쿠폰 타입은 필수입니다.")
        CouponType couponType,

        @NotNull(message = "할인 값은 필수입니다.")
        @Min(value = 1, message = "할인 값은 1 이상이어야 합니다.")
        Integer discountValue,

        @NotNull(message = "쿠폰 시작 시간은 필수입니다.")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime startTime,

        @NotNull(message = "쿠폰 종료 시간은 필수입니다.")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime endTime,

        @NotNull(message = "쿠폰 만료 시간은 필수입니다.")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime expireTime
) {
    public CouponCreateRequest {
        validateTimeSequence(startTime, endTime, expireTime);
        validateDiscountValue(couponType, discountValue);
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

    private void validateDiscountValue(CouponType couponType, Integer discountValue) {
        if (couponType == CouponType.PERCENTAGE) {
            if (discountValue <= 0 || discountValue > 100) {
                throw new IllegalArgumentException("퍼센트 할인율은 1~100 사이여야 합니다.");
            }
        } else if (couponType == CouponType.FIXED_AMOUNT && discountValue <= 0) {
            throw new IllegalArgumentException("고정 금액 할인은 0보다 커야 합니다.");
        }

    }

    public static CouponCreateRequest from(Coupon coupon) {
        return new CouponCreateRequest(
                coupon.getName(),
                coupon.getCode(),
                coupon.getTotalStock(),
                coupon.getType(),
                coupon.getDiscountValue(),
                coupon.getStartTime(),
                coupon.getEndTime(),
                coupon.getExpireTime()
        );
    }

    public Coupon toEntity() {
        return Coupon.builder()
                .name(name)
                .code(code)
                .totalStock(totalStock)
                .type(couponType)
                .discountValue(discountValue)
                .startTime(startTime)
                .endTime(endTime)
                .expireTime(expireTime)
                .build();
    }
}