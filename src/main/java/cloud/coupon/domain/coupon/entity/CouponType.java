package cloud.coupon.domain.coupon.entity;

public enum CouponType {
    FIXED_AMOUNT("정액 할인"),
    PERCENTAGE("정률 할인");

    private String description;

    CouponType(String description) {
        this.description = description;
    }
}
