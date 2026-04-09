package cloud.coupon.api.v2;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.dto.response.TicketResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/coupons")
@RequiredArgsConstructor
public class CouponV2Controller {

    private final CouponIssueProducer couponIssueProducer;

    @PostMapping("/issue")
    public ResponseEntity<TicketResponse> issueCoupon(@RequestBody CouponIssueRequest request) {
        TicketResponse ticket = couponIssueProducer.issue(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ticket);
    }
}
