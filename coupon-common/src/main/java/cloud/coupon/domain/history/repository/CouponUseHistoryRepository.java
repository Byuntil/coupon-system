package cloud.coupon.domain.history.repository;

import cloud.coupon.domain.history.entity.CouponUseHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponUseHistoryRepository extends JpaRepository<CouponUseHistory, Long> {
}
