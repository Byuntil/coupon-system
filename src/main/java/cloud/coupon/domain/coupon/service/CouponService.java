package cloud.coupon.domain.coupon.service;

import cloud.coupon.domain.coupon.admin.dto.response.CouponStatusResponse;
import cloud.coupon.domain.coupon.dto.response.CouponIssueResult;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponIssue;
import cloud.coupon.domain.coupon.entity.IssueResult;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.domain.coupon.util.CodeGenerator;
import cloud.coupon.domain.history.entity.CouponIssueHistory;
import cloud.coupon.domain.history.repository.CouponIssueHistoryRepository;
import cloud.coupon.global.error.exception.CouponNotFoundException;
import cloud.coupon.global.error.exception.DuplicateCouponException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {
    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueHistoryRepository couponIssueHistoryRepository;
    private final CodeGenerator couponCodeGenerator;

    // 쿠폰 발급
    @Transactional
    public CouponIssueResult issueCoupon(Long couponId, Long userId, String requestIp) {
        // 발급 로직
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CouponNotFoundException("존재하지 않는 쿠폰입니다."));
        try {
            validateDuplicateIssue(couponId, userId, coupon, requestIp);

            coupon.issue();

            CouponIssue couponIssue = createCouponIssue(userId, coupon);
            saveCouponIssueHistory(coupon, userId, requestIp, IssueResult.SUCCESS, null);

            return CouponIssueResult.success(couponIssue.getId());
        } catch (Exception e) {
            saveCouponIssueHistory(coupon, userId, requestIp, IssueResult.FAIL, e.getMessage());
            return CouponIssueResult.fail(e.getMessage());
        }
    }

    private void validateDuplicateIssue(Long couponId, Long userId, Coupon coupon, String requestIp) {
        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new DuplicateCouponException("이미 발급된 쿠폰입니다.");
        }
    }

    public CouponStatusResponse getCouponStatus(Long couponId) {
        // 조회 로직
        return null;
    }

    // 쿠폰 사용 처리 - 데이터 수정 필요

    @Transactional
    public void useCoupon(Long couponIssueId) {
        // 사용 처리 로직
    }

    private CouponIssue createCouponIssue(Long userId, Coupon coupon) {
        String issueCode = couponCodeGenerator.generateCode();

        return couponIssueRepository.save(
                CouponIssue.builder()
                        .coupon(coupon)
                        .userId(userId)
                        .issueCode(issueCode)
                        .build());
    }

    private void saveCouponIssueHistory(Coupon coupon, Long userId, String requestIp,
                                        IssueResult result, String failReason) {
        couponIssueHistoryRepository.save(
                CouponIssueHistory.builder()
                        .coupon(coupon)
                        .userId(userId)
                        .requestIp(requestIp)
                        .result(result)
                        .failReason(failReason)
                        .build()
        );
    }
}
