package cloud.coupon.domain.history.repository;

import cloud.coupon.domain.coupon.entity.IssueResult;
import cloud.coupon.domain.history.entity.CouponIssueHistory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 쿠폰 발급 시도 이력 관리 발급 성공 / 실패 이력 관리
 */
@Repository
public interface CouponIssueHistoryRepository extends JpaRepository<CouponIssueHistory, Long> {
    //사용자별 발급 이력 조회 (최신순)
    List<CouponIssueHistory> findByUserIdOrderByRequestTimeDesc(Long userId);

    //특정 쿠폰에 대한 사용자의 발급 이력
    Optional<CouponIssueHistory> findByCodeAndUserId(String code, Long userId);

    //성공/실패 여부로 조회
    List<CouponIssueHistory> findByUserIdAndResult(Long userId, IssueResult result);

    void deleteByCode(String code);

    long countByCodeAndResult(String code, IssueResult result);

    // 순서 역전 건수: 실패 요청 중 서버 수신 시점이 성공 요청보다 빠른 케이스
    @Query("""
            SELECT COUNT(f) FROM CouponIssueHistory f
            WHERE f.code = :code
              AND f.result = 'FAIL'
              AND f.serverReceivedAtNanos IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM CouponIssueHistory s
                  WHERE s.code = f.code
                    AND s.result = 'SUCCESS'
                    AND f.serverReceivedAtNanos < s.serverReceivedAtNanos
              )
            """)
    long countOrderingViolations(@Param("code") String code);
}
