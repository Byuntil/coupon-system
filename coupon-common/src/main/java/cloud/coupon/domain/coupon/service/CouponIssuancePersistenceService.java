package cloud.coupon.domain.coupon.service;

import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_NOT_FOUND_MESSAGE;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.dto.response.CouponIssueResult;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponIssue;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.domain.coupon.util.CodeGenerator;
import cloud.coupon.domain.history.service.CouponIssueHistoryService;
import cloud.coupon.global.error.exception.coupon.CouponNotAvailableException;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import cloud.coupon.global.error.exception.coupon.CouponOutOfStockException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssuancePersistenceService {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueHistoryService couponIssueHistoryService;
    private final CodeGenerator couponCodeGenerator;

    /**
     * Redis가 재고를 예약한 후 호출되는 DB 트랜잭션.
     * saveAndFlush로 유니크 제약 위반을 즉시 드러내고,
     * decreaseRemainStockAtomically로 DB 최종 재고 보장.
     */
    @Transactional
    public CouponIssueResult issueReservedCoupon(CouponIssueRequest request) {
        // 1. 쿠폰 조회 및 발급 기간 검증
        Coupon coupon = couponRepository.findByCodeAndIsDeletedFalse(request.code())
                .orElseThrow(() -> new CouponNotFoundException(COUPON_NOT_FOUND_MESSAGE));

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getStartTime()) || now.isAfter(coupon.getEndTime())) {
            throw new CouponNotAvailableException("쿠폰 발급 기간이 아닙니다.");
        }

        // 2. DB 원자 재고 감소 — 먼저 exclusive lock 획득 (deadlock 방지)
        // coupon_issue INSERT(FK shared lock) 보다 먼저 실행해야 lock 순서 일관성 보장
        int updated = couponRepository.decreaseRemainStockAtomically(request.code(), now);

        // 3. 0건이면 DB 재고 소진 → 전체 롤백
        if (updated == 0) {
            throw new CouponOutOfStockException("쿠폰 재고가 소진되었습니다.");
        }

        // 4. CouponIssue saveAndFlush — 유니크 제약(coupon_id + user_id) 위반 즉시 드러냄
        String issuedCode = couponCodeGenerator.generateCode();
        CouponIssue couponIssue = CouponIssue.builder()
                .coupon(coupon)
                .userId(request.userId())
                .issuedCode(issuedCode)
                .build();
        couponIssueRepository.saveAndFlush(couponIssue);

        // 5. 성공 history 저장 (같은 트랜잭션)
        couponIssueHistoryService.saveSuccessHistory(request.code(), request.userId(), request.requestIp(), request.serverReceivedAtNanos());

        log.info("[{}]: DB 발급 완료 | userId: {} issuedCode: {}", request.code(), request.userId(), issuedCode);
        return CouponIssueResult.success(issuedCode);
    }

    /**
     * DB-only 전략 경로: 비관적 락 + coupon.issue() + CouponIssue 저장.
     */
    @Transactional
    public CouponIssueResult issueWithDbLock(CouponIssueRequest request) {
        Coupon coupon = couponRepository.findByCodeWithLock(request.code())
                .orElseThrow(() -> new CouponNotFoundException(COUPON_NOT_FOUND_MESSAGE));

        // 발급 가능 검증 및 DB 재고 감소 (remainStock--)
        coupon.issue();

        // CouponIssue 저장
        String issuedCode = couponCodeGenerator.generateCode();
        couponIssueRepository.save(
                CouponIssue.builder()
                        .coupon(coupon)
                        .userId(request.userId())
                        .issuedCode(issuedCode)
                        .build()
        );

        // 성공 history 저장
        couponIssueHistoryService.saveSuccessHistory(request.code(), request.userId(), request.requestIp(), request.serverReceivedAtNanos());

        log.info("[{}]: DB-only 발급 완료 | userId: {} issuedCode: {}", request.code(), request.userId(), issuedCode);
        return CouponIssueResult.success(issuedCode);
    }
}
