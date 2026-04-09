package cloud.coupon.domain.coupon.service.strategy;

public interface CouponIssuanceStrategy {

    // fast-fail optimization only. correctness is decided by decreaseStock().
    boolean hasStock(String couponCode);

    boolean decreaseStock(String couponCode);

    void increaseStock(String couponCode);

    boolean requiresDbLock();
}
