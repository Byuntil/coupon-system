package cloud.coupon.domain.coupon.service;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.dto.response.CouponIssueResult;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponIssue;
import cloud.coupon.domain.coupon.entity.IssueResult;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.domain.coupon.util.CodeGenerator;
import cloud.coupon.domain.history.entity.CouponIssueHistory;
import cloud.coupon.domain.history.repository.CouponIssueHistoryRepository;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import cloud.coupon.global.error.exception.coupon.DuplicateCouponException;
import cloud.coupon.global.error.exception.couponissue.CouponIssueNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {
    public static final String COUPON_NOT_FOUND_MESSAGE = "존재하지 않는 쿠폰입니다.";

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueHistoryRepository couponIssueHistoryRepository;
    private final CodeGenerator couponCodeGenerator;

    // 쿠폰 발급
    @Transactional
    public CouponIssueResult issueCoupon(CouponIssueRequest request) {
        // 발급 로직
        Coupon coupon = couponRepository.findByCode(request.code())
                .orElseThrow(() -> new CouponNotFoundException(COUPON_NOT_FOUND_MESSAGE));
        try {
            validateDuplicateIssue(request.code(), request.userId());

            coupon.issue();

            CouponIssue couponIssue = createCouponIssue(request.userId(), coupon);
            saveCouponIssueHistory(coupon, request.userId(), request.requestIp(), IssueResult.SUCCESS, null);

            return CouponIssueResult.success(couponIssue.getIssuedCode());
        } catch (Exception e) {
            saveCouponIssueHistory(coupon, request.userId(), request.requestIp(), IssueResult.FAIL, e.getMessage());
            return CouponIssueResult.fail(e.getMessage());
        }
    }

    // 쿠폰 사용 처리 - 데이터 수정 필요
    @Transactional
    public void useCoupon(String issueCode, Long userId) {
        // 사용 처리 로직
        CouponIssue couponIssue = couponIssueRepository.findByIssuedCodeAndUserId(issueCode, userId)
                .orElseThrow(() -> new CouponIssueNotFoundException("발급된 쿠폰을 찾을 수 없습니다."));

        couponIssue.use();
    }

    private void validateDuplicateIssue(String code, Long userId) {
        if (couponIssueRepository.existsByCouponCodeAndUserId(code, userId)) {
            throw new DuplicateCouponException("이미 발급된 쿠폰입니다.");
        }
    }

    private CouponIssue createCouponIssue(Long userId, Coupon coupon) {
        String issuedCode = couponCodeGenerator.generateCode();

        return couponIssueRepository.save(
                CouponIssue.builder()
                        .coupon(coupon)
                        .userId(userId)
                        .issuedCode(issuedCode)
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
