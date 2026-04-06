package cloud.coupon.domain.coupon.service;

import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_DUPLICATE_ERROR_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_ISSUE_NOT_FOUND_MESSAGE;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.dto.response.CouponIssueResult;
import cloud.coupon.domain.coupon.dto.response.CouponUseResponse;
import cloud.coupon.domain.coupon.entity.CouponIssue;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.domain.coupon.service.strategy.CouponIssuanceStrategy;
import cloud.coupon.domain.history.service.CouponIssueHistoryService;
import cloud.coupon.domain.history.entity.CouponUseHistory;
import cloud.coupon.domain.history.repository.CouponUseHistoryRepository;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import cloud.coupon.global.error.exception.coupon.DuplicateCouponException;
import cloud.coupon.global.error.exception.couponissue.CouponIssueNotFoundException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {
    private final AtomicInteger currentLoadFactor = new AtomicInteger(0);

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponUseHistoryRepository couponUseHistoryRepository;
    private final CouponIssuanceStrategy issuanceStrategy;
    private final CouponIssuancePersistenceService couponIssuancePersistenceService;
    private final CouponIssueHistoryService couponIssueHistoryService;

    // 쿠폰 발급 — 오케스트레이터 (트랜잭션 없음: Redis·DB 경로 각각 자체 트랜잭션)
    public CouponIssueResult issueCoupon(CouponIssueRequest request) {
        log.debug("[{}]: 쿠폰 발급 시도 시작 | userId: {}", request.code(), request.userId());

        long startTime = System.currentTimeMillis();
        currentLoadFactor.incrementAndGet();
        try {
            if (issuanceStrategy.requiresDbLock()) {
                return issueWithDbLock(request);
            }
            return issueWithRedis(request);
        } finally {
            log.info("[{}]: 발급 요청 처리 완료 | 소요시간: {}ms | 부하: {}",
                    request.code(),
                    System.currentTimeMillis() - startTime,
                    currentLoadFactor.get());
            currentLoadFactor.decrementAndGet();
        }
    }

    /**
     * Redis 경로: Redis 원자 예약 → 별도 DB 트랜잭션.
     * 분산락 없음. 유니크 제약이 중복 발급을 최종 보장.
     */
    private CouponIssueResult issueWithRedis(CouponIssueRequest request) {
        boolean reserved = false;
        boolean compensated = false;

        // fast-fail (최적화용; correctness는 decreaseStock()이 보장)
        if (!issuanceStrategy.hasStock(request.code())) {
            couponIssueHistoryService.saveFailureHistory(
                    request.code(), request.userId(), request.requestIp(), "재고 소진");
            return CouponIssueResult.fail("쿠폰이 모두 소진되었습니다.");
        }

        // Redis 원자 재고 예약
        if (!issuanceStrategy.decreaseStock(request.code())) {
            couponIssueHistoryService.saveFailureHistory(
                    request.code(), request.userId(), request.requestIp(), "재고 소진");
            return CouponIssueResult.fail("쿠폰이 모두 소진되었습니다.");
        }
        reserved = true;

        try {
            // DB 트랜잭션 (자체 @Transactional)
            return couponIssuancePersistenceService.issueReservedCoupon(request);
        } catch (DuplicateCouponException | DataIntegrityViolationException e) {
            // 유니크 제약 위반 → Redis 보상
            if (!compensated) {
                issuanceStrategy.increaseStock(request.code());
                compensated = true;
            }
            couponIssueHistoryService.saveFailureHistory(
                    request.code(), request.userId(), request.requestIp(), "중복 발급");
            throw new DuplicateCouponException(COUPON_DUPLICATE_ERROR_MESSAGE);
        } catch (CouponNotFoundException e) {
            if (!compensated) {
                issuanceStrategy.increaseStock(request.code());
                compensated = true;
            }
            couponIssueHistoryService.saveFailureHistory(
                    request.code(), request.userId(), request.requestIp(), e.getMessage());
            throw e;
        } catch (Exception e) {
            // DB 실패 → Redis 보상
            if (reserved && !compensated) {
                issuanceStrategy.increaseStock(request.code());
                compensated = true;
            }
            log.error("[{}]: DB 발급 실패 | userId: {} | 원인: {}",
                    request.code(), request.userId(), e.getMessage());
            couponIssueHistoryService.saveFailureHistory(
                    request.code(), request.userId(), request.requestIp(), e.getMessage());
            return CouponIssueResult.fail("쿠폰 발급이 불가능합니다.");
        }
    }

    /**
     * DB-only 경로: 비관적 락 + coupon.issue() + CouponIssue 저장.
     * CouponIssuancePersistenceService.issueWithDbLock()이 @Transactional 보장.
     */
    private CouponIssueResult issueWithDbLock(CouponIssueRequest request) {
        // 중복 발급 사전 검증 (DB-only 경로에서만 수행 — 비관적 락 내에서 안전)
        if (couponIssueRepository.existsByCouponCodeAndUserId(request.code(), request.userId())) {
            couponIssueHistoryService.saveFailureHistory(
                    request.code(), request.userId(), request.requestIp(), "중복 발급");
            throw new DuplicateCouponException(COUPON_DUPLICATE_ERROR_MESSAGE);
        }

        try {
            return couponIssuancePersistenceService.issueWithDbLock(request);
        } catch (Exception e) {
            log.error("[{}]: DB-only 발급 실패 | userId: {} | 원인: {}",
                    request.code(), request.userId(), e.getMessage());
            couponIssueHistoryService.saveFailureHistory(
                    request.code(), request.userId(), request.requestIp(), e.getMessage());
            throw e;
        }
    }

    // 쿠폰 사용
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
