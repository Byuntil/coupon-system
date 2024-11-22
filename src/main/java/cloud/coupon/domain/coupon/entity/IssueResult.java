package cloud.coupon.domain.coupon.entity;

public enum IssueResult {
    SUCCESS("발급 성공"),
    FAIL("발급 실패");

    private String description;

    IssueResult(String description) {
        this.description = description;
    }
}
