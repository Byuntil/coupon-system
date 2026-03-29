package cloud.coupon.domain.coupon.service.strategy;

public interface CouponIssuanceStrategy {

    boolean acquireLock(String couponCode, String requestId);

    void releaseLock(String couponCode, String requestId);

    boolean decreaseStock(String couponCode);

    void increaseStock(String couponCode);
}
