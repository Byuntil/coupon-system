package cloud.coupon.domain.coupon.repository;

import cloud.coupon.domain.coupon.entity.Coupon;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * 쿠폰 마스터 데이터 관리 쿠폰 재고 관리
 */
@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {
    Optional<Coupon> findByCode(String code);

    @Query("SELECT c FROM Coupon c WHERE c.code = :code AND c.isDeleted = false")
    Optional<Coupon> findByCodeAndIsDeletedFalse(String code);

    @Query("SELECT EXISTS (SELECT 1 FROM Coupon c WHERE c.code = :code AND c.isDeleted = false)")
    boolean existsActiveCodeAndNotDeleted(String code);
    //재고 업데이트 등

}
