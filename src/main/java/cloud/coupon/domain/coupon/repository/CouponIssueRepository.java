package cloud.coupon.domain.coupon.repository;

import cloud.coupon.domain.coupon.entity.CouponIssue;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 사용자별 쿠폰 발급 내역 관리 중복 발급 체크
 */
@Repository
public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {
    boolean existsByCouponCodeAndUserId(String code, Long userId); //중복 발급 체크

    Optional<CouponIssue> findByIssuedCodeAndUserId(String code, Long userId); // 발급된 쿠폰 조회
}
