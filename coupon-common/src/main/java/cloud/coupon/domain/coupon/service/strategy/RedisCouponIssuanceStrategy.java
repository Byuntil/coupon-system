package cloud.coupon.domain.coupon.service.strategy;

import cloud.coupon.infra.redis.service.RedisStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "redis", matchIfMissing = true)
public class RedisCouponIssuanceStrategy implements CouponIssuanceStrategy {

    private final RedisStockService redisStockService;

    @Override
    public boolean hasStock(String couponCode) {
        return redisStockService.hasStock(couponCode);
    }

    @Override
    public boolean decreaseStock(String couponCode) {
        return redisStockService.decreaseStock(couponCode);
    }

    @Override
    public void increaseStock(String couponCode) {
        redisStockService.increaseStock(couponCode);
    }

    @Override
    public boolean requiresDbLock() {
        return false;
    }
}
