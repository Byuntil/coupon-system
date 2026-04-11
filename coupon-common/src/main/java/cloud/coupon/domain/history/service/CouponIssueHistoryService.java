package cloud.coupon.domain.history.service;

import cloud.coupon.domain.coupon.entity.IssueResult;
import cloud.coupon.domain.history.entity.CouponIssueHistory;
import cloud.coupon.domain.history.repository.CouponIssueHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueHistoryService {

    private final CouponIssueHistoryRepository couponIssueHistoryRepository;

    /**
     * 성공 history를 호출자의 트랜잭션에 참여해 저장합니다.
     * 발급 트랜잭션이 롤백되면 함께 롤백됩니다.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void saveSuccessHistory(String code, Long userId, String requestIp, Long serverReceivedAtNanos) {
        couponIssueHistoryRepository.save(
                CouponIssueHistory.builder()
                        .code(code)
                        .userId(userId)
                        .requestIp(requestIp)
                        .serverReceivedAtNanos(serverReceivedAtNanos)
                        .result(IssueResult.SUCCESS)
                        .failReason(null)
                        .build()
        );
    }

    /**
     * 실패 history를 메인 트랜잭션과 독립적으로 저장합니다.
     * REQUIRES_NEW로 별도 트랜잭션을 열어 메인 흐름의 롤백과 무관하게 저장됩니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveFailureHistory(String code, Long userId, String requestIp, Long serverReceivedAtNanos, String failReason) {
        try {
            couponIssueHistoryRepository.save(
                    CouponIssueHistory.builder()
                            .code(code)
                            .userId(userId)
                            .requestIp(requestIp)
                            .serverReceivedAtNanos(serverReceivedAtNanos)
                            .result(IssueResult.FAIL)
                            .failReason(failReason)
                            .build()
            );
        } catch (Exception e) {
            log.error("실패 history 저장 실패 | code: {} userId: {}", code, userId, e);
            // best-effort: 실패해도 무시
        }
    }
}
