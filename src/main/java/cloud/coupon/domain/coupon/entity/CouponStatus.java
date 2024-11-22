package cloud.coupon.domain.coupon.entity;

public enum CouponStatus {
    READY("발급 대기"),
    ACTIVE("발급 중"),
    EXHAUSTED("소진됨"),
    STOPPED("발급 중단");

    private String description;

    CouponStatus(String description) {
        this.description = description;
    }
}
