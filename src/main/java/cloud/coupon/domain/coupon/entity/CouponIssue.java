package cloud.coupon.domain.coupon.entity;

import cloud.coupon.global.error.exception.coupon.CouponAlreadyUsedException;
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

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Coupon coupon;

    private Long userId;
    private String issueCode; //발급된 고유 쿠폰 코드
    private boolean used;
    private LocalDateTime issuedAt; //발급 시간
    private LocalDateTime usedAt; //사용시간

    @Enumerated(EnumType.STRING)
    private CouponIssueStatus status; //상태(발급완료/사용완료/만료)

    @Builder
    public CouponIssue(Coupon coupon, Long userId, String issueCode) {
        this.coupon = coupon;
        this.userId = userId;
        this.issueCode = issueCode;
        this.used = false;
        this.issuedAt = LocalDateTime.now();
        this.status = CouponIssueStatus.ISSUED;
    }

    public void use() {
        validateForUse();
        this.used = true;
        this.usedAt = LocalDateTime.now();
        this.status = CouponIssueStatus.USED;
        coupon.increaseUsedCount();
    }

    private void validateForUse() {
        if (used) {
            throw new CouponAlreadyUsedException("이미 사용된 쿠폰입니다.");
        }
        coupon.validateForUse();
    }
}
