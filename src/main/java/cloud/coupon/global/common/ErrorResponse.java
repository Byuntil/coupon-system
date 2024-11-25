package cloud.coupon.global.common;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ErrorResponse {
    private String message;
    private Map<String, String> details;

    public ErrorResponse(String message, String details) {
        this.message = message;
        this.details = Map.of("error", details);
    }
}