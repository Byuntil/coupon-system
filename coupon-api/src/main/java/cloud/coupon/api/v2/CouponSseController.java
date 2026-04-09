package cloud.coupon.api.v2;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v2/coupons")
@RequiredArgsConstructor
public class CouponSseController {

    private final SseEmitterManager sseEmitterManager;

    @GetMapping(value = "/subscribe/{ticketId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String ticketId) {
        return sseEmitterManager.subscribe(ticketId);
    }
}
