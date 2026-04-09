package cloud.coupon.domain.history.entity;

import cloud.coupon.domain.coupon.entity.CouponIssue;
import cloud.coupon.domain.coupon.entity.CouponType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponUseHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private CouponIssue couponIssue;

    private Long userId;

    @Enumerated(EnumType.STRING)
    private CouponType discountType;
    private Integer discountValue;

    @Column(updatable = false)
    private LocalDateTime usedAt;

    @Builder
    public CouponUseHistory(CouponIssue couponIssue, Long userId,
                            CouponType discountType, Integer discountValue,
                            LocalDateTime usedAt) {
        this.couponIssue = couponIssue;
        this.userId = userId;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.usedAt = usedAt;
    }
}
