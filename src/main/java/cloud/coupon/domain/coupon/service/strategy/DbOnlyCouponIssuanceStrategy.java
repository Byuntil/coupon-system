package cloud.coupon.domain.coupon.service.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "db-only")
public class DbOnlyCouponIssuanceStrategy implements CouponIssuanceStrategy {

    @Override
    public boolean acquireLock(String couponCode, String requestId) {
        // DB 비관적 락(SELECT FOR UPDATE)에 의존 — 별도 분산 락 불필요
        return true;
    }

    @Override
    public void releaseLock(String couponCode, String requestId) {
        // no-op: DB 트랜잭션 종료 시 자동 해제
    }

    @Override
    public boolean decreaseStock(String couponCode) {
        // no-op: DB의 Coupon.issue()에서 remainStock 감소
        return true;
    }

    @Override
    public void increaseStock(String couponCode) {
        // no-op: DB 트랜잭션 롤백으로 복구
    }
}
