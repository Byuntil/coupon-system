package cloud.coupon.domain.coupon.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cloud.coupon.infra.redis.service.RedisStockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponIssuanceStrategyTest {

    @Test
    @DisplayName("Redis 전략은 DB 비관적 락을 필요로 하지 않는다")
    void redisCouponIssuanceStrategy_requiresDbLock_returnsFalse() {
        RedisStockService mockService = mock(RedisStockService.class);
        RedisCouponIssuanceStrategy strategy = new RedisCouponIssuanceStrategy(mockService);

        assertThat(strategy.requiresDbLock()).isFalse();
    }

    @Test
    @DisplayName("DB-only 전략은 DB 비관적 락을 필요로 한다")
    void dbOnlyCouponIssuanceStrategy_requiresDbLock_returnsTrue() {
        DbOnlyCouponIssuanceStrategy strategy = new DbOnlyCouponIssuanceStrategy();

        assertThat(strategy.requiresDbLock()).isTrue();
    }
}
