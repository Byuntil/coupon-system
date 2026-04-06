package cloud.coupon.domain.coupon.repository;

import cloud.coupon.domain.coupon.entity.Coupon;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 쿠폰 마스터 데이터 관리 쿠폰 재고 관리
 */
@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {
    Optional<Coupon> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.code = :code AND c.isDeleted = false")
    Optional<Coupon> findByCodeWithLock(String code);

    @Query("SELECT c FROM Coupon c WHERE c.code = :code AND c.isDeleted = false")
    Optional<Coupon> findByCodeAndIsDeletedFalse(String code);

    @Query("SELECT EXISTS (SELECT 1 FROM Coupon c WHERE c.code = :code AND c.isDeleted = false)")
    boolean existsActiveCodeAndNotDeleted(String code);

    @Query("SELECT c FROM Coupon c WHERE c.status = 'ACTIVE' AND c.isDeleted = false")
    List<Coupon> findAllActiveCoupons();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE coupon
            SET remain_stock = remain_stock - 1,
                status = CASE
                    WHEN remain_stock - 1 = 0 THEN 'EXHAUSTED'
                    ELSE status
                END
            WHERE code = :code
              AND is_deleted = false
              AND remain_stock > 0
              AND start_time <= :now
              AND end_time >= :now
            """, nativeQuery = true)
    int decreaseRemainStockAtomically(@Param("code") String code, @Param("now") LocalDateTime now);
}
