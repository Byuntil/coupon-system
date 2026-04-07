package cloud.coupon.infra.redis.service;

import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_NOT_FOUND_MESSAGE;

import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import cloud.coupon.global.error.exception.redis.RedisOperationException;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStockService {
    private final RedisTemplate<String, String> redisTemplate;
    private final CouponRepository couponRepository;
    private static final String STOCK_KEY_PREFIX = "coupon:stock:";

    @PostConstruct
    public void initializeStockData() {
        List<Coupon> activeCoupons = couponRepository.findAllActiveCoupons();
        for (Coupon coupon : activeCoupons) {
            syncStockWithDB(coupon.getCode(), coupon.getRemainStock());
        }
    }

    public void initializeStock(String couponCode, int quantity) {
        String key = STOCK_KEY_PREFIX + couponCode;
        redisTemplate.opsForValue().set(key, String.valueOf(quantity));
    }

    public void increaseStock(String code) {
        String key = STOCK_KEY_PREFIX + code;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                redisTemplate.opsForValue().increment(key);
                log.info("[{}] Redis 재고 증가 완료 (시도 {})", code, attempt + 1);
                return;
            } catch (Exception e) {
                log.warn("[{}] Redis 재고 증가 실패 (시도 {}/3): {}", code, attempt + 1, e.getMessage());
                if (attempt == 2) {
                    log.error("[{}] Redis 재고 증가 최종 실패", code, e);
                    throw new RedisOperationException("Redis 재고 증가 중 오류가 발생했습니다.");
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RedisOperationException("Redis 재고 증가 중 인터럽트 발생");
                }
            }
        }
    }

    // fast-fail optimization only. correctness is decided by decreaseStock().
    // Redis 키 자체가 없으면 true를 반환해 decreaseStock()에서 CouponNotFoundException을 던지게 한다.
    public boolean hasStock(String couponCode) {
        String value = redisTemplate.opsForValue().get(STOCK_KEY_PREFIX + couponCode);
        if (value == null) {
            return true; // 키 없음 — decreaseStock()이 -2 반환하며 CouponNotFoundException 처리
        }
        return Long.parseLong(value) > 0;
    }

    public boolean decreaseStock(String couponCode) {
        String key = STOCK_KEY_PREFIX + couponCode;
        String script =
                "local current = redis.call('get', KEYS[1]) " +
                "if not current then " +
                "    return -2 " +
                "end " +
                "if tonumber(current) <= 0 then " +
                "    return -1 " +
                "end " +
                "return redis.call('decr', KEYS[1])";

        long result = redisTemplate.execute(
                new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(key)
        );

        if (result == -2) {
            log.error("[{}]: 존재하지 않은 쿠폰", couponCode);
            throw new CouponNotFoundException(COUPON_NOT_FOUND_MESSAGE);
        }
        if (result < 0) {
            log.warn("[{}]: 쿠폰 재고 부족", couponCode);
            return false;
        }
        return true;
    }

    public void removeStockKey(String couponCode) {
        String key = STOCK_KEY_PREFIX + couponCode;
        redisTemplate.delete(key);
        log.info("[{}] Redis 재고 키 삭제 완료", couponCode);
    }

    public void deleteAllKeys() {
        deleteKeysByPattern(STOCK_KEY_PREFIX + "*");
    }

    public void syncStockWithDB(String code, int dbStock) {
        String stockKey = STOCK_KEY_PREFIX + code;
        redisTemplate.opsForValue().set(stockKey, String.valueOf(dbStock));
        log.info("[{}] Redis 재고 동기화. 설정값: {}", code, dbStock);
    }


    private void deleteKeysByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
