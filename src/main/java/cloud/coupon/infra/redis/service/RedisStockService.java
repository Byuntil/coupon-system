package cloud.coupon.infra.redis.service;

import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import cloud.coupon.global.error.exception.redis.RedisLockException;
import cloud.coupon.global.error.exception.redis.RedisOperationException;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
    private static final String LOCK_KEY_PREFIX = "coupon:lock:";

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
        try {
            redisTemplate.opsForValue().increment(key);
            log.info("[{}] Redis 재고 증가 완료", code);

            String currentStock = redisTemplate.opsForValue().get(key);

            if (currentStock != null) {
                Coupon coupon = couponRepository.findByCodeAndIsDeletedFalse(code)
                        .orElseThrow(() -> new CouponNotFoundException("쿠폰을 찾을 수 없습니다."));

                int redisStock = Integer.parseInt(currentStock);
                if (redisStock > coupon.getTotalStock()) {
                    // Redis 재고가 총 재고를 초과하면 조정합니다
                    redisTemplate.opsForValue().set(key,
                            String.valueOf(coupon.getTotalStock()));
                    log.warn("[{}] Redis 재고가 총 재고를 초과하여 조정됨. Redis:{}, DB:{}",
                            code, redisStock, coupon.getTotalStock());
                }
            }
        } catch (Exception e) {
            log.error("[{}] Redis 재고 증가 실패 : {}", code, e.getMessage());
            throw new RedisOperationException("Redis 재고 증가 중 오류가 발생했습니다.");
        }
    }

    public boolean decreaseStock(String couponCode) {
        String key = STOCK_KEY_PREFIX + couponCode;
        Long remainStock = redisTemplate.opsForValue().decrement(key);
        if (remainStock == null || remainStock < 0) {
            //재고가 부족한 경우 원복
            if (remainStock != null) {
                redisTemplate.opsForValue().increment(key);
            }
            return false;
        }
        return true;
    }

    public void deleteStock(String couponCode) {
        String key = STOCK_KEY_PREFIX + couponCode;

        String currentStock = redisTemplate.opsForValue().get(key);

        if (currentStock == null) {
            log.warn("[{}] Redis에 재고 정보가 없습니다. DB에서 동기화를 시도합니다.", couponCode);

            Coupon coupon = couponRepository.findByCodeAndIsDeletedFalse(couponCode)
                    .orElseThrow(() -> new CouponNotFoundException("쿠폰을 찾을 수 없습니다."));

            syncStockWithDB(couponCode, coupon.getRemainStock());
            currentStock = String.valueOf(coupon.getRemainStock());
        }

        if (Integer.parseInt(currentStock) <= 0) {
            log.info("[{}] 재고 부족으로 쿠폰 발급 실패. 현재 재고: {}", couponCode, currentStock);
            return;
        }

        try {
            String script =
                    "local current = redis.call('get', KEYS[1]) " +
                            "if current and tonumber(current) > 0 then " +
                            "   return redis.call('decr', KEYS[1]) " +
                            "else " +
                            "   return -1 " +
                            "end";

            Long remainStock = redisTemplate.execute(
                    new DefaultRedisScript<>(script, Long.class),
                    Collections.singletonList(key)
            );

            if (remainStock < 0) {
                log.info("[{}] 재고 감소 실패. 남은 재고: {}", couponCode, remainStock);
                return;
            }

            log.debug("[{}] 재고 감소 성공. 남은 재고: {}", couponCode, remainStock);
        } catch (Exception e) {
            log.error("[{}] 재고 감소 중 오류 발생: {}", couponCode, e.getMessage(), e);
            throw new RedisOperationException("재고 감소 처리 중 오류가 발생했습니다.");
        }
    }

    public void deleteAllKeys() {
        String stockPattern = STOCK_KEY_PREFIX + "*";
        deleteKeysByPattern(stockPattern);

        String lockPattern = LOCK_KEY_PREFIX + "*";
        deleteKeysByPattern(lockPattern);
    }

    public void syncStockWithDB(String code, int dbStock) {
        String stockKey = STOCK_KEY_PREFIX + code;
        String lockKey = LOCK_KEY_PREFIX + code;
        String lockValue = UUID.randomUUID().toString();

        boolean locked = acquireLock(lockKey, lockValue);
        if (!locked) {
            log.warn("[{}] 재고 동기화를 위한 락 획득 실패", code);
            throw new RedisLockException("재고 동기화를 위한 락 획득에 실패했습니다.");
        }

        try {
            String currentStock = redisTemplate.opsForValue().get(stockKey);

            if (currentStock == null) {
                redisTemplate.opsForValue().set(stockKey, String.valueOf(dbStock));
                log.info("[{}] Redis 재고 초기화 완료. 설정된 재고: {}", code, dbStock);
            } else {
                int redisStock = Integer.parseInt(currentStock);

                if (redisStock != dbStock) {
                    log.warn("[{}] Redis와 DB 재고 불일치 감지. Redis: {}, DB: {}",
                            code, redisStock, dbStock);

                    redisTemplate.opsForValue().set(stockKey, String.valueOf(dbStock));
                    log.info("[{}] Redis 재고를 DB 재고와 동기화 완료. 설정된 재고: {}",
                            code, dbStock);
                }
            }
        } catch (Exception e) {
            log.error("[{}] Redis-DB 재고 동기화 실패: {}", code, e.getMessage(), e);
            throw new RedisOperationException("Redis-DB 재고 동기화 중 오류가 발생했습니다.");
        } finally {
            releaseLock(lockKey, lockValue);
        }
    }

    public boolean acquireLock(String couponCode, String requestId) {
        String lockKey = LOCK_KEY_PREFIX + couponCode;
        long deadline = System.currentTimeMillis() + 3000; // 3초 동안 시도

        while (System.currentTimeMillis() < deadline) {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, requestId, Duration.ofSeconds(5));

            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }

            try {
                Thread.sleep(100); // 100ms 대기 후 재시도
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    public void releaseLock(String couponCode, String requestId) {
        String lockKey = LOCK_KEY_PREFIX + couponCode;
        String script =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "   return redis.call('del', KEYS[1]) " +
                        "else " +
                        "   return 0 " +
                        "end";

        try {
            redisTemplate.execute(
                    new DefaultRedisScript<>(script, Long.class),
                    Collections.singletonList(lockKey),
                    requestId
            );
        } catch (Exception e) {
            log.warn("락 해제 중 에러 발생. lockKey: {}, requestId: {}", lockKey, requestId, e);
        }
    }

    private void deleteKeysByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
