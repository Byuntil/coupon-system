package cloud.coupon.domain.coupon.service.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "db-only")
public class DbOnlyCouponIssuanceStrategy implements CouponIssuanceStrategy {

    @Override
    public boolean hasStock(String couponCode) {
        // no-op: DB 비관적 락과 decreaseRemainStockAtomically()에서 재고 확인
        return true;
    }

    @Override
    public boolean decreaseStock(String couponCode) {
        // no-op: DB의 decreaseRemainStockAtomically()에서 remainStock 감소
        return true;
    }

    @Override
    public void increaseStock(String couponCode) {
        // no-op: DB 트랜잭션 롤백으로 복구
    }

    @Override
    public boolean requiresDbLock() {
        return true;
    }
}
