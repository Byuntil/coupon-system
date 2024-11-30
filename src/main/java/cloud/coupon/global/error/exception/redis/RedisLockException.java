package cloud.coupon.global.error.exception.redis;

public class RedisLockException extends RedisException {
    public RedisLockException(String message) {
        super(message);
    }
}
