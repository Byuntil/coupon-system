package cloud.coupon.domain.coupon.entity;

public enum CouponIssueStatus {
    ISSUED("발급됨"),
    USED("사용됨"),
    EXPIRED("만료됨");

    private String description;

    CouponIssueStatus(String description) {
        this.description = description;
    }
}
