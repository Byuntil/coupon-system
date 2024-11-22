package cloud.coupon.domain.coupon.entity;

import cloud.coupon.global.error.exception.CouponOutOfStockException;
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
    private Integer totalStock;
    private Integer remainStock;

    @Enumerated(EnumType.STRING)
    private CouponType type;
    private Integer discountValue;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime expireTime;

    @Enumerated(EnumType.STRING)
    private CouponStatus status;

    private boolean isDeleted;

    @Builder
    public Coupon(String name, String code, Integer totalStock, Integer remainStock, CouponType type,
                  Integer discountValue, LocalDateTime startTime, LocalDateTime endTime, LocalDateTime expireTime) {
        this.name = name;
        this.code = code;
        this.totalStock = totalStock;
        this.remainStock = remainStock;
        this.type = type;
        this.discountValue = discountValue;
        this.startTime = startTime;
        this.endTime = endTime;
        this.expireTime = expireTime;
        this.status = CouponStatus.ACTIVE;
        this.isDeleted = false;
    }

    public void issue() {
        if (remainStock <= 0) {
            throw new CouponOutOfStockException("쿠폰 재고가 소진되었습니다.");
        }
        this.remainStock--;
        //재고 소진시 상태변경
        if (remainStock == 0) {
            this.status = CouponStatus.EXHAUSTED;
        }
    }

    public void markAsDeleted() {
        this.isDeleted = true;
    }

    public void changeStatus(CouponStatus newStatus) {
        this.status = newStatus;
    }
}
