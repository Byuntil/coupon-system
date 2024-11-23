package cloud.coupon.domain.coupon.constant;

public class ErrorMessage {
    public static final String COUPON_NOT_FOUND_MESSAGE = "존재하지 않는 쿠폰입니다.";
    public static final String COUPON_ALREADY_EXISTS_MESSAGE = "이미 존재하는 쿠폰입니다.";
    public static final String COUPON_ISSUE_NOT_FOUND_MESSAGE = "발급된 쿠폰을 찾을 수 없습니다.";
    public static final String COUPON_DUPLICATE_ERROR_MESSAGE = "중복된 쿠폰이 존재합니다.";
    public static final String COUPON_ALREADY_USED_ERROR_MESSAGE = "이미 사용된 쿠폰입니다.";
    public static final String COUPON_ALREADY_DELETED_ERROR_MESSAGE = "이미 삭제된 쿠폰입니다.";

    private ErrorMessage() {
    }
}
