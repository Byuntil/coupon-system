package cloud.coupon.domain.coupon.service;

import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_DUPLICATE_ERROR_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_ISSUE_NOT_FOUND_MESSAGE;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.dto.response.CouponIssueResult;
import cloud.coupon.domain.coupon.dto.response.CouponUseResponse;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponIssue;
import cloud.coupon.domain.coupon.entity.IssueResult;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.domain.coupon.util.CodeGenerator;
import cloud.coupon.domain.history.entity.CouponIssueHistory;
import cloud.coupon.domain.history.entity.CouponUseHistory;
import cloud.coupon.domain.history.repository.CouponIssueHistoryRepository;
import cloud.coupon.domain.history.repository.CouponUseHistoryRepository;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import cloud.coupon.global.error.exception.coupon.DuplicateCouponException;
import cloud.coupon.global.error.exception.couponissue.CouponIssueNotFoundException;
import cloud.coupon.infra.redis.service.RedisStockService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {
    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueHistoryRepository couponIssueHistoryRepository;
    private final CouponUseHistoryRepository couponUseHistoryRepository;
    private final CodeGenerator couponCodeGenerator;
    private final RedisStockService redisStockService;

    //쿠폰 발급
    @Transactional
    public CouponIssueResult issueCoupon(CouponIssueRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.debug("[{}]: 쿠폰 발급 시도 시작 | requestId: {} userId: {}", request.code(), requestId, request.userId());

        //중복 발급 검증
        validateDuplicateIssue(request.code(), request.userId());
        //유효한 쿠폰에 대해서만 분산 락 시도
        if (!lockCouponForIssuance(request, requestId)) {
            return CouponIssueResult.fail("처리 중입니다. 잠시 후 다시 시도해주세요.");
        }
        //Redis 재고 감소 시도
        if (!attemptToDecreaseStock(request.code(), requestId)) {
            saveCouponIssueHistory(request.code(), request.userId(), request.requestIp(), IssueResult.FAIL,
                    "쿠폰이 모두 소진되었습니다.");
            return CouponIssueResult.fail("쿠폰이 모두 소진되었습니다.");
        }
        //쿠폰 존재 여부 확인
        Coupon coupon = findValidCoupon(request.code());

        return processCouponIssuance(request, requestId, coupon);
    }

    // 쿠폰 사용 처리 - 데이터 수정 필요
    @Transactional
    public CouponUseResponse useCoupon(Long userId, String issueCode) {
        log.debug("[{}]: 쿠폰 사용 시도 시작 | userId: {}", issueCode, userId);
        // 사용 처리 로직
        try {
            CouponIssue couponIssue = couponIssueRepository.findByIssuedCodeAndUserId(issueCode, userId)
                    .orElseThrow(() -> new CouponIssueNotFoundException(COUPON_ISSUE_NOT_FOUND_MESSAGE));

            couponIssue.use();
            log.debug("[{}]: 쿠폰 사용 성공 | userId: {}", issueCode, userId);

            CouponUseHistory useHistory = createCouponUseHistory(userId, couponIssue);
            couponUseHistoryRepository.save(useHistory);

            return new CouponUseResponse(
                    true,
                    useHistory.getDiscountValue(),
                    useHistory.getUsedAt()
            );
        } catch (CouponIssueNotFoundException e) {
            log.error("[{}]: 쿠폰 사용 실패 | userId : {} | 원인 : {}", issueCode, userId, e.getMessage());
            throw e;
        }
    }

    private boolean lockCouponForIssuance(CouponIssueRequest request, String requestId) {
        while (true) {
            if (redisStockService.acquireLock(request.code(), requestId)) {
                return true;
            }

            log.info("[{}]: 분산 락 획득 실패 | requestId: {} userId: {}", request.code(), requestId, request.userId());

            try {
                Thread.sleep(100); // 잠시 대기 후 재시도
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private Coupon findValidCoupon(String couponCode) {
        return couponRepository.findByCodeAndIsDeletedFalse(couponCode)
                .orElseThrow(() -> new CouponNotFoundException(COUPON_ISSUE_NOT_FOUND_MESSAGE));
    }

    private CouponIssueResult processCouponIssuance(CouponIssueRequest request, String requestId, Coupon coupon) {
        try {
            //DB 재고 감소 및 쿠폰 발급 처리
            return issueCouponAndRecordHistory(request, requestId, coupon);
        } catch (Exception e) {
            saveCouponIssueHistory(request.code(), request.userId(), request.requestIp(), IssueResult.FAIL,
                    e.getMessage());
            return CouponIssueResult.fail(e.getMessage());
        } finally {
            redisStockService.releaseLock(request.code(), requestId);
        }
    }

    private boolean attemptToDecreaseStock(String couponCode, String requestId) {
        long stockCheckStart = System.currentTimeMillis();
        if (!redisStockService.decreaseStock(couponCode)) {
            log.info("[{}]: 재고 부족으로 발급 실패 | requestId: {}", couponCode, requestId);
            return false;
        }
        log.debug("[{}]: 재고 감소 처리 시간: {}ms | requestId: {}", couponCode, System.currentTimeMillis() - stockCheckStart,
                requestId);
        return true;
    }

    private CouponIssueResult issueCouponAndRecordHistory(CouponIssueRequest request, String requestId, Coupon coupon) {
        try {
            coupon.issue();

            CouponIssue couponIssue = createCouponIssue(request.userId(), coupon);
            saveCouponIssueHistory(request.code(), request.userId(), request.requestIp(), IssueResult.SUCCESS, null);

            log.info("[{}]: 쿠폰 발급 성공 | requestId: {} userId: {}", request.code(), requestId, request.userId());
            return CouponIssueResult.success(couponIssue.getIssuedCode());
        } catch (Exception e) {
            //DB 작업 실패시 Redis 재고 원복
            redisStockService.increaseStock(request.code());
            throw e;
        }
    }

    private void validateDuplicateIssue(String code, Long userId) {
        if (couponIssueRepository.existsByCouponCodeAndUserId(code, userId)) {
            throw new DuplicateCouponException(COUPON_DUPLICATE_ERROR_MESSAGE);
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

    private void saveCouponIssueHistory(String code, Long userId, String requestIp,
                                        IssueResult result, String failReason) {
        couponIssueHistoryRepository.save(
                CouponIssueHistory.builder()
                        .code(code)
                        .userId(userId)
                        .requestIp(requestIp)
                        .result(result)
                        .failReason(failReason)
                        .build()
        );
    }

    private CouponUseHistory createCouponUseHistory(Long userId, CouponIssue couponIssue) {
        return CouponUseHistory.builder()
                .couponIssue(couponIssue)
                .userId(userId)
                .discountType(couponIssue.getCoupon().getType())
                .discountValue(couponIssue.getCoupon().getDiscountValue())
                .usedAt(couponIssue.getUsedAt())
                .build();
    }
}
