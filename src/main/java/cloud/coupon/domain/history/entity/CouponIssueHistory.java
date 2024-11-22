package cloud.coupon.domain.history.entity;

import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.IssueResult;
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
public class CouponIssueHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Coupon coupon;

    private Long userId;
    private String requestIp;              // 요청 IP
    private LocalDateTime requestTime;      // 요청 시간

    @Enumerated(EnumType.STRING)
    private IssueResult result;            // 발급 결과(성공/실패)
    private String failReason;             // 실패 사유

    @Builder
    public CouponIssueHistory(Coupon coupon, Long userId, String requestIp, IssueResult result, String failReason) {
        this.coupon = coupon;
        this.userId = userId;
        this.requestIp = requestIp;
        this.requestTime = LocalDateTime.now();
        this.result = result;
        this.failReason = failReason;
    }
}