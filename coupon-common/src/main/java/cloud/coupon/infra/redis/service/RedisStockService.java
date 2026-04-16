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
    private static final String INFLIGHT_KEY_PREFIX = "coupon:inflight:";
    private static final String ISSUED_KEY_PREFIX = "coupon:issued:";
    private static final String STREAM_KEY = "coupon:issue:stream";
    private static final String PHASE3_ADMIN_LOCK_KEY = "coupon:loadtest:phase3:admin:lock";

    private static final String ISSUE_LUA_SCRIPT = """
            local inflight_key = KEYS[1]
            local issued_key   = KEYS[2]
            local stock_key    = KEYS[3]
            local stream_key   = KEYS[4]
            local user_id      = ARGV[1]
            local ticket_id    = ARGV[2]
            local code         = ARGV[3]
            local request_ip   = ARGV[4]
            local request_time = ARGV[5]

            if redis.call('sismember', issued_key, user_id) == 1 then
                return -3
            end
            if redis.call('sismember', inflight_key, user_id) == 1 then
                return -4
            end
            local stock = redis.call('get', stock_key)
            if not stock then return -2 end
            if tonumber(stock) <= 0 then return -1 end

            redis.call('decr', stock_key)
            redis.call('sadd', inflight_key, user_id)
            redis.call('xadd', stream_key, '*',
                'ticketId', ticket_id,
                'code', code,
                'userId', user_id,
                'requestIp', request_ip,
                'requestTime', request_time)

            return tonumber(redis.call('get', stock_key))
            """;

    private static final String TRANSITION_TO_ISSUED_LUA_SCRIPT = """
            local inflight_key = KEYS[1]
            local issued_key = KEYS[2]
            local user_id = ARGV[1]

            if redis.call('sismember', issued_key, user_id) == 1 then
                return 1
            end
            if redis.call('sismember', inflight_key, user_id) == 0 then
                return 0
            end

            redis.call('srem', inflight_key, user_id)
            redis.call('sadd', issued_key, user_id)
            return 1
            """;

    private static final String ROLLBACK_INFLIGHT_LUA_SCRIPT = """
            local inflight_key = KEYS[1]
            local issued_key = KEYS[2]
            local stock_key = KEYS[3]
            local user_id = ARGV[1]

            if redis.call('sismember', issued_key, user_id) == 1 then
                return -1
            end
            if redis.call('sismember', inflight_key, user_id) == 0 then
                return 0
            end
            if redis.call('exists', stock_key) == 0 then
                return -2
            end

            redis.call('srem', inflight_key, user_id)
            return redis.call('incr', stock_key)
            """;

    private static final String RELEASE_PHASE3_ADMIN_LOCK_LUA_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """;

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
        incrementWithRetry(STOCK_KEY_PREFIX + code, code);
    }

    private void incrementWithRetry(String key, String code) {
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

    /**
     * Phase 3 비동기 발급에서 사용하는 모든 Redis 키 삭제.
     * stock, inflight, issued, ticket 패턴을 모두 정리한다.
     * Stream과 DLQ는 별도로 삭제해야 한다 (RedisStreamService 또는 직접 삭제).
     */
    public void deleteAllPhase3Keys() {
        deleteKeysByPattern(STOCK_KEY_PREFIX + "*");
        deleteKeysByPattern(INFLIGHT_KEY_PREFIX + "*");
        deleteKeysByPattern(ISSUED_KEY_PREFIX + "*");
        deleteKeysByPattern("coupon:ticket:*");
    }

    public RedisTemplate<String, String> getRedisTemplate() {
        return redisTemplate;
    }

    public boolean tryAcquirePhase3AdminLock(String couponCode) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(PHASE3_ADMIN_LOCK_KEY, couponCode);
        return Boolean.TRUE.equals(acquired);
    }

    public String getPhase3AdminLockOwner() {
        return redisTemplate.opsForValue().get(PHASE3_ADMIN_LOCK_KEY);
    }

    public boolean releasePhase3AdminLock(String couponCode) {
        Long released = redisTemplate.execute(
                new DefaultRedisScript<>(RELEASE_PHASE3_ADMIN_LOCK_LUA_SCRIPT, Long.class),
                Collections.singletonList(PHASE3_ADMIN_LOCK_KEY),
                couponCode
        );
        return released != null && released > 0;
    }

    public void syncStockWithDB(String code, int dbStock) {
        String stockKey = STOCK_KEY_PREFIX + code;
        redisTemplate.opsForValue().set(stockKey, String.valueOf(dbStock));
        log.info("[{}] Redis 재고 동기화. 설정값: {}", code, dbStock);
    }


    /**
     * 통합 Lua: 중복체크(issued+inflight) + 재고차감 + XADD 원자적 수행.
     * 반환값: >= 0 성공(남은 재고), -1 재고소진, -2 쿠폰없음, -3 발급완료중복, -4 처리중중복
     */
    public long issueAtomically(String couponCode, String userId, String ticketId, String requestIp, String requestTime) {
        List<String> keys = List.of(
                INFLIGHT_KEY_PREFIX + couponCode,
                ISSUED_KEY_PREFIX + couponCode,
                STOCK_KEY_PREFIX + couponCode,
                STREAM_KEY
        );
        List<String> args = List.of(userId, ticketId, couponCode, requestIp, requestTime);

        return redisTemplate.execute(
                new DefaultRedisScript<>(ISSUE_LUA_SCRIPT, Long.class),
                keys,
                args.toArray()
        );
    }

    /**
     * Consumer 성공 시: inflight → issued 전이
     */
    public void transitionToIssued(String couponCode, String userId) {
        Long result = redisTemplate.execute(
                new DefaultRedisScript<>(TRANSITION_TO_ISSUED_LUA_SCRIPT, Long.class),
                List.of(INFLIGHT_KEY_PREFIX + couponCode, ISSUED_KEY_PREFIX + couponCode),
                userId
        );

        if (result != null && result == 0L) {
            log.warn("[{}] inflight -> issued 전이 스킵 | userId: {}", couponCode, userId);
        }
    }

    /**
     * Consumer 실패/DLQ 시: inflight에서 제거 + 재고 복구
     */
    public void rollbackInflight(String couponCode, String userId) {
        Long result = redisTemplate.execute(
                new DefaultRedisScript<>(ROLLBACK_INFLIGHT_LUA_SCRIPT, Long.class),
                List.of(
                        INFLIGHT_KEY_PREFIX + couponCode,
                        ISSUED_KEY_PREFIX + couponCode,
                        STOCK_KEY_PREFIX + couponCode
                ),
                userId
        );

        if (result == null) {
            throw new RedisOperationException("Redis inflight 롤백 결과를 확인할 수 없습니다.");
        }
        if (result == -2L) {
            throw new RedisOperationException("Redis 재고 키가 없어 inflight 롤백을 완료할 수 없습니다.");
        }
        if (result == -1L) {
            log.warn("[{}] inflight 롤백 스킵(이미 issued) | userId: {}", couponCode, userId);
            return;
        }
        if (result == 0L) {
            log.warn("[{}] inflight 롤백 스킵(이미 처리됨) | userId: {}", couponCode, userId);
        }
    }

    private void deleteKeysByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
