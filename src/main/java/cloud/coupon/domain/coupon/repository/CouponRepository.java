package cloud.coupon.domain.coupon.repository;

import cloud.coupon.domain.coupon.entity.Coupon;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 쿠폰 마스터 데이터 관리 쿠폰 재고 관리
 */
@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {
    Optional<Coupon> findById(Long couponId);
    //재고 업데이트 등
}
