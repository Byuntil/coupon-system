package cloud.coupon.domain.history.repository;

import cloud.coupon.domain.coupon.entity.IssueResult;
import cloud.coupon.domain.history.entity.CouponIssueHistory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
