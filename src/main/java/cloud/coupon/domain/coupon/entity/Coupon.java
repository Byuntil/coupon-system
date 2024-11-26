package cloud.coupon.domain.coupon.entity;

import cloud.coupon.domain.coupon.admin.dto.request.CouponUpdateRequest;
import cloud.coupon.global.error.exception.coupon.CouponNotAvailableException;
import cloud.coupon.global.error.exception.coupon.CouponOutOfStockException;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String code; //템플릿 코드
    private Integer totalStock; // 총 재고 수량
    private Integer remainStock; //발급 가능한 수
    private Integer usedCount; //사용된 수량

    @Enumerated(EnumType.STRING)
    private CouponType type;
    private Integer discountValue;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime; //발급 시작 시간
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime; //발급 종료 시간
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime; //만료 시간

    @Enumerated(EnumType.STRING)
    private CouponStatus status;

    private boolean isDeleted;

    @Builder
    public Coupon(String name, String code, Integer totalStock, CouponType type,
                  Integer discountValue, LocalDateTime startTime, LocalDateTime endTime, LocalDateTime expireTime) {
        this.name = name;
        this.code = code;
        this.totalStock = totalStock;
        this.remainStock = totalStock;
        this.usedCount = 0;
        this.type = type;
        this.discountValue = discountValue;
        this.startTime = startTime;
        this.endTime = endTime;
        this.expireTime = expireTime;
        this.status = CouponStatus.ACTIVE;
        this.isDeleted = false;
    }

    //발급
    public void issue() {
        validateForIssue();

        this.remainStock--;
        //재고 소진시 상태변경
        if (remainStock == 0) {
            this.status = CouponStatus.EXHAUSTED;
        }
    }

    public void update(CouponUpdateRequest request) {
        validateUpdateTime(request.startTime(), request.endTime(), request.expireTime());

        this.name = request.name();
        this.totalStock = request.totalStock();
        this.discountValue = request.discountValue();
        this.type = request.couponType();
        this.startTime = request.startTime();
        this.endTime = request.endTime();
        this.expireTime = request.expireTime();
    }

    private void validateUpdateTime(LocalDateTime startTime, LocalDateTime endTime, LocalDateTime expireTime) {
        if (startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("발급 시작 시간은 종료 시간보다 이전이어야 합니다.");
        }
        if (endTime.isAfter(expireTime)) {
            throw new IllegalArgumentException("발급 종료 시간은 만료 시간보다 이전이어야 합니다.");
        }
    }

    //쿠폰 사용
    public void increaseUsedCount() {
        this.usedCount++;
    }

    public void markAsDeleted() {
        this.isDeleted = true;
    }

    public void changeStatus(CouponStatus newStatus) {
        this.status = newStatus;
    }

    public boolean isAllUsed() {
        return usedCount >= totalStock;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expireTime);
    }

    public void validateForUse() {
        if (!isAvailable()) {
            throw new CouponNotAvailableException("사용 불가능한 쿠폰입니다.");
        }
    }

    private void validateForIssue() {
        if (remainStock <= 0) {
            throw new CouponOutOfStockException("쿠폰 재고가 소진되었습니다.");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(startTime) || now.isAfter(expireTime)) {
            throw new CouponNotAvailableException("쿠폰 발급 기간이 아닙니다.");
        }
    }

    private boolean isAvailable() {
        return !isDeleted &&
                !isExpired() &&
                !isAllUsed() &&
                (status == CouponStatus.ACTIVE || status == CouponStatus.EXHAUSTED);
    }
}
