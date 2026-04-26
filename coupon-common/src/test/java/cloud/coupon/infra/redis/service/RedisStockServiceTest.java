package cloud.coupon.infra.redis.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cloud.coupon.domain.coupon.repository.CouponRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class RedisStockServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void skipsStartupStockSyncWhenDisabled() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        CouponRepository couponRepository = mock(CouponRepository.class);
        RedisStockService service = new RedisStockService(redisTemplate, couponRepository);
        ReflectionTestUtils.setField(service, "syncStockOnStartup", false);

        service.initializeStockData();

        verify(couponRepository, never()).findAllActiveCoupons();
    }
}
