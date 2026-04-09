package cloud.coupon.global.error.exception.redis;

public class RedisException extends RuntimeException {
    public RedisException(String message) {
        super(message);
    }
}
