package cloud.coupon.domain.coupon.service;

import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_DUPLICATE_ERROR_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_ISSUE_NOT_FOUND_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_NOT_FOUND_MESSAGE;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {
    private final AtomicInteger currentLoadFactor = new AtomicInteger(0);
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueHistoryRepository couponIssueHistoryRepository;
    private final CouponUseHistoryRepository couponUseHistoryRepository;
    private final CodeGenerator couponCodeGenerator;
    private final RedisStockService redisStockService;

    //1. 쿠폰 발급
    @Transactional
    public CouponIssueResult issueCoupon(CouponIssueRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.debug("[{}]: 쿠폰 발급 시도 시작 | requestId: {} userId: {}",
                request.code(), requestId, request.userId());

        //2. 중복 발급 검증
        validateDuplicateIssue(request.code(), request.userId());

        return processCouponIssueRequest(request, requestId);
    }

    private void validateDuplicateIssue(String code, Long userId) {
        if (couponIssueRepository.existsByCouponCodeAndUserId(code, userId)) {
            throw new DuplicateCouponException(COUPON_DUPLICATE_ERROR_MESSAGE);
        }
    }

    private CouponIssueResult processCouponIssueRequest(CouponIssueRequest request, String requestId) {
        long startTime = System.currentTimeMillis();
        currentLoadFactor.incrementAndGet();

        try {
            //3. 쿠폰 발급을 위한 lock 획득
            if (!redisStockService.acquireLock(request.code(), requestId)) {
                failureCount.incrementAndGet();
                saveCouponIssueHistory(request.code(), request.userId(), request.requestIp(),
                        IssueResult.FAIL, "분산 락 획득 실패");
                return CouponIssueResult.fail("서버가 혼잡합니다. 잠시 후 다시 시도해주세요.");
            }

            return getCouponIssueResult(request, requestId);
        } finally {
            log.info("[{}]: 발급 요청 처리 완료 | 소요시간: {}ms | 부하: {} | 성공률: {}%",
                    request.code(),
                    System.currentTimeMillis() - startTime,
                    currentLoadFactor.get(),
                    String.format("%.2f", calculateSuccessRate())
            );
        }
    }

    private CouponIssueResult getCouponIssueResult(CouponIssueRequest request, String requestId) {
        //4. 재고 감소
        try {
            return processIssuanceWithLock(request, requestId);
        } finally {
            redisStockService.releaseLock(request.code(), requestId);
            currentLoadFactor.decrementAndGet();
        }
    }

    private CouponIssueResult processIssuanceWithLock(CouponIssueRequest request, String requestId) {
        // 4. Redis 재고 감소 시도
        if (!attemptToDecreaseStock(request.code(), requestId)) {
            failureCount.incrementAndGet();
            saveCouponIssueHistory(request.code(), request.userId(), request.requestIp(),
                    IssueResult.FAIL, "재고 소진");
            return CouponIssueResult.fail("쿠폰이 모두 소진되었습니다.");
        }

        try {
            // 5. 쿠폰 존재 여부 확인 및 발급 처리
            Coupon coupon = findValidCoupon(request.code());
            return issueCouponAndRecordHistory(request, requestId, coupon);
        } catch (Exception e) {
            // 실패 시 Redis 재고 복구
            redisStockService.increaseStock(request.code());
            failureCount.incrementAndGet();
            saveCouponIssueHistory(request.code(), request.userId(), request.requestIp(),
                    IssueResult.FAIL, e.getMessage());
            return CouponIssueResult.fail(e.getMessage());
        }
    }

    private boolean attemptToDecreaseStock(String couponCode, String requestId) {
        long stockCheckStart = System.currentTimeMillis();
        boolean decreased = redisStockService.decreaseStock(couponCode);

        log.debug("[{}]: 재고 감소 처리 시간: {}ms | requestId: {}",
                couponCode,
                System.currentTimeMillis() - stockCheckStart,
                requestId);

        return decreased;
    }

    private Coupon findValidCoupon(String couponCode) {
        return couponRepository.findByCodeAndIsDeletedFalse(couponCode)
                .orElseThrow(() -> new CouponNotFoundException(COUPON_NOT_FOUND_MESSAGE));
    }

    private CouponIssueResult issueCouponAndRecordHistory(
            CouponIssueRequest request,
            String requestId,
            Coupon coupon) {
        try {
            // 쿠폰 발급 가능 여부 확인 및 재고 감소
            coupon.issue();

            // 쿠폰 발급 정보 저장
            CouponIssue couponIssue = createCouponIssue(request.userId(), coupon);

            // 발급 이력 기록
            saveCouponIssueHistory(
                    request.code(),
                    request.userId(),
                    request.requestIp(),
                    IssueResult.SUCCESS,
                    null
            );

            successCount.incrementAndGet();
            log.info("[{}]: 쿠폰 발급 성공 | requestId: {} userId: {}",
                    request.code(), requestId, request.userId());

            return CouponIssueResult.success(couponIssue.getIssuedCode());
        } catch (Exception e) {
            log.error("[{}]: 쿠폰 발급 실패 | requestId: {} userId: {} | 원인: {}",
                    request.code(), requestId, request.userId(), e.getMessage());
            throw e;
        }
    }

    private CouponIssue createCouponIssue(Long userId, Coupon coupon) {
        String issuedCode = couponCodeGenerator.generateCode();

        return couponIssueRepository.save(
                CouponIssue.builder()
                        .coupon(coupon)
                        .userId(userId)
                        .issuedCode(issuedCode)
                        .build()
        );
    }

    private void saveCouponIssueHistory(
            String code,
            Long userId,
            String requestIp,
            IssueResult result,
            String failReason) {
        try {
            couponIssueHistoryRepository.save(
                    CouponIssueHistory.builder()
                            .code(code)
                            .userId(userId)
                            .requestIp(requestIp)
                            .result(result)
                            .failReason(failReason)
                            .build()
            );
        } catch (Exception e) {
            log.error("발급 이력 저장 실패 | code: {} userId: {} result: {} | 원인: {}",
                    code, userId, result, e.getMessage());
        }
    }

    private double calculateSuccessRate() {
        long totalAttempts = successCount.get() + failureCount.get();
        if (totalAttempts == 0) {
            return 100.0;
        }
        return (successCount.get() * 100.0) / totalAttempts;
    }

    // 쿠폰 사용 관련 메서드
    @Transactional
    public CouponUseResponse useCoupon(Long userId, String issueCode) {
        log.debug("[{}]: 쿠폰 사용 시도 시작 | userId: {}", issueCode, userId);

        try {
            CouponIssue couponIssue = findCouponIssue(userId, issueCode);
            couponIssue.use();

            CouponUseHistory useHistory = createAndSaveUseHistory(userId, couponIssue);

            log.debug("[{}]: 쿠폰 사용 성공 | userId: {}", issueCode, userId);

            return new CouponUseResponse(
                    true,
                    useHistory.getDiscountValue(),
                    useHistory.getUsedAt()
            );
        } catch (Exception e) {
            log.error("[{}]: 쿠폰 사용 실패 | userId: {} | 원인: {}",
                    issueCode, userId, e.getMessage());
            throw e;
        }
    }

    private CouponIssue findCouponIssue(Long userId, String issueCode) {
        return couponIssueRepository.findByIssuedCodeAndUserId(issueCode, userId)
                .orElseThrow(() -> new CouponIssueNotFoundException(COUPON_ISSUE_NOT_FOUND_MESSAGE));
    }

    private CouponUseHistory createAndSaveUseHistory(Long userId, CouponIssue couponIssue) {
        CouponUseHistory useHistory = CouponUseHistory.builder()
                .couponIssue(couponIssue)
                .userId(userId)
                .discountType(couponIssue.getCoupon().getType())
                .discountValue(couponIssue.getCoupon().getDiscountValue())
                .usedAt(couponIssue.getUsedAt())
                .build();

        return couponUseHistoryRepository.save(useHistory);
    }
}