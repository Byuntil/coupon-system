package cloud.coupon.domain.coupon.entity;

public enum CouponStatus {
    ACTIVE("활성 상태"),
    EXHAUSTED("소진됨"),
    EXPIRED("만료됨"),
    DISABLED("비활성화됨");

    private String description;

    CouponStatus(String description) {
        this.description = description;
    }
}
