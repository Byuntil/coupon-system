package cloud.coupon.domain.coupon.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CouponIssueResult {
    private ResultType resultType;
    private String couponCode;  // 성공시에만 값이 있음
    private String message;

    public static CouponIssueResult success(String couponCode) {
        return new CouponIssueResult(ResultType.SUCCESS, couponCode, "쿠폰이 정상적으로 발급되었습니다.");
    }

    public static CouponIssueResult fail(String message) {
        return new CouponIssueResult(ResultType.FAIL, null, message);
    }

    public enum ResultType {
        SUCCESS,
        FAIL
    }

    public boolean isSuccess() {
        return resultType == ResultType.SUCCESS;
    }
}
