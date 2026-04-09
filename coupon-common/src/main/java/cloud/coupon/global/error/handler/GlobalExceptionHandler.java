package cloud.coupon.global.error.handler;

import cloud.coupon.global.common.ErrorResponse;
import cloud.coupon.global.error.exception.coupon.CouponException;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import cloud.coupon.global.error.exception.couponissue.CouponIssueException;
import cloud.coupon.global.error.exception.couponissue.CouponIssueNotFoundException;
import cloud.coupon.global.error.exception.redis.RedisException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));

        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Validation Error", errors));
    }

    @ExceptionHandler(CouponNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCouponNotFoundException(CouponNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("Coupon Not Found", ex.getMessage()));
    }

    @ExceptionHandler(CouponIssueNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCouponIssueNotFoundException(CouponIssueNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("Coupon Issue Not Found", ex.getMessage()));
    }

    @ExceptionHandler(CouponException.class)
    public ResponseEntity<ErrorResponse> handleCouponException(CouponException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Coupon Error", ex.getMessage()));
    }

    @ExceptionHandler(CouponIssueException.class)
    public ResponseEntity<ErrorResponse> handleCouponIssueException(CouponIssueException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Coupon Issue Error", ex.getMessage()));
    }

    @ExceptionHandler(RedisException.class)
    public ResponseEntity<ErrorResponse> handleRedisException(RedisException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("Service Temporarily Unavailable", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllExceptions(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal Server Error", ex.getMessage()));
    }
}
