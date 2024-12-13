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
import java.util.Random;
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

    // 락 획득 시도 관련 상수
    private static final int MAX_RETRY_COUNT = 20;          // 최대 재시도 횟수
    private static final long LOCK_TIMEOUT_SECONDS = 3;     // 락의 유효 시간
    private static final long ACQUIRE_TIMEOUT_MS = 10000;    // 락 획득 최대 대기 시간

    // 백오프 관련 상수
    private static final long INITIAL_BACKOFF_MS = 50;      // 초기 대기 시간
    private static final long MAX_BACKOFF_MS = 800;         // 최대 대기 시간
    private static final double BACKOFF_MULTIPLIER = 1.5;   // 백오프 승수
    private static final double JITTER_FACTOR = 0.15;       // 지터 범위 (±15%)

    private final Random random = new Random();

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

    /**
     * 분산 락 획득을 시도합니다. CSMA/CD 방식으로 충돌을 감지하고 지수 백오프로 재시도합니다.
     */
    public boolean acquireLock(String couponCode, String requestId) {
        String lockKey = LOCK_KEY_PREFIX + couponCode;
        int retryCount = 0;
        long backoffTime = INITIAL_BACKOFF_MS;
        long deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT_MS;

        // 타임아웃까지 재시도
        while (System.currentTimeMillis() < deadline && retryCount < MAX_RETRY_COUNT) {
            // Carrier Sense: 현재 락의 상태 확인
            if (!isLockHeld(lockKey)) {
                // 락 획득 시도: NX 옵션으로 원자적 생성, EX 옵션으로 만료 시간 설정
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, requestId, Duration.ofSeconds(LOCK_TIMEOUT_SECONDS));

                if (Boolean.TRUE.equals(acquired)) {
                    log.debug("[Lock-{}] 락 획득 성공 | 시도 횟수: {} | 요청ID: {}",
                            couponCode, retryCount + 1, requestId);
                    return true;
                }

                // 락 획득 실패 로깅
                log.debug("[Lock-{}] 락 획득 실패 | 시도 횟수: {} | 요청ID: {}",
                        couponCode, retryCount + 1, requestId);
            }

            // Collision Detection & Exponential Backoff
            retryCount++;
            if (System.currentTimeMillis() < deadline) {
                backoffTime = calculateBackoffTime(retryCount, backoffTime);

                log.trace("[Lock-{}] 재시도 대기 | 대기시간: {}ms | 남은시도: {}",
                        couponCode, backoffTime, MAX_RETRY_COUNT - retryCount);

                try {
                    Thread.sleep(backoffTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[Lock-{}] 락 획득 중단 | 요청ID: {}", couponCode, requestId);
                    return false;
                }
            }
        }

        // 락 획득 최종 실패
        long elapsedTime = System.currentTimeMillis() - (deadline - ACQUIRE_TIMEOUT_MS);
        log.warn("[Lock-{}] 락 획득 최종 실패 | 소요시간: {}ms | 시도횟수: {} | 요청ID: {}",
                couponCode, elapsedTime, retryCount, requestId);
        return false;
    }

    /**
     * 현재 락이 설정되어 있는지 확인합니다. Redis의 key 존재 여부로 판단합니다.
     */
    private boolean isLockHeld(String lockKey) {
        Boolean exists = redisTemplate.hasKey(lockKey);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 지수 백오프 시간을 계산합니다. 재시도 횟수와 현재 대기 시간을 모두 고려하여 다음 대기 시간을 결정합니다. 재시도 횟수가 증가할수록 백오프 승수가 커지며, 현재 대기 시간을 기준으로 점진적으로
     * 증가시킵니다.
     *
     * @param retryCount     현재까지의 재시도 횟수 (승수 결정에 사용)
     * @param currentBackoff 현재의 대기 시간 (기준값으로 사용)
     * @return 다음 재시도까지 대기할 시간 (밀리초)
     */
    private long calculateBackoffTime(int retryCount, long currentBackoff) {
        // 재시도 횟수에 따라 승수를 증가시킵니다 (1.5, 2.0, 2.5, 3.0)
        double multiplier = BACKOFF_MULTIPLIER + (Math.min(retryCount, 4) * 0.5);

        // 현재 대기 시간에 승수를 적용하여 다음 대기 시간을 계산합니다
        long nextBackoff = (long) (currentBackoff * multiplier);

        // 최대 대기 시간으로 제한합니다
        long cappedBackoff = Math.min(nextBackoff, MAX_BACKOFF_MS);

        // 재시도 횟수가 많을수록 지터의 범위를 줄임
        double adjustedJitterFactor = JITTER_FACTOR / (1 + (retryCount * 0.1));
        long jitterRange = (long) (cappedBackoff * adjustedJitterFactor);
        long jitter = (random.nextLong() * 2 - 1) * jitterRange;

        // 최종 대기 시간은 최소값과 최대값 사이로 제한
        long min = Math.min(cappedBackoff + jitter, MAX_BACKOFF_MS);
        return Math.max(INITIAL_BACKOFF_MS, min);
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
