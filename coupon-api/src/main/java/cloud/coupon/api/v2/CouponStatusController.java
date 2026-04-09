package cloud.coupon.api.v2;

import cloud.coupon.domain.coupon.dto.response.TicketResponse;
import cloud.coupon.infra.redis.service.RedisTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/coupons")
@RequiredArgsConstructor
public class CouponStatusController {

    private final RedisTicketService redisTicketService;

    @GetMapping("/status/{ticketId}")
    public ResponseEntity<TicketResponse> getStatus(@PathVariable String ticketId) {
        return redisTicketService.getTicket(ticketId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
