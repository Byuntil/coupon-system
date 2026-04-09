package cloud.coupon.api.v2;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.dto.response.TicketResponse;
import cloud.coupon.domain.coupon.dto.response.TicketStatus;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import cloud.coupon.global.error.exception.coupon.CouponOutOfStockException;
import cloud.coupon.global.error.exception.coupon.DuplicateCouponException;
import cloud.coupon.infra.redis.service.RedisStockService;
import cloud.coupon.infra.redis.service.RedisTicketService;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueProducer {

    private final RedisStockService redisStockService;
    private final RedisTicketService redisTicketService;

    public TicketResponse issue(CouponIssueRequest request) {
        String ticketId = "tkt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String requestTime = LocalDateTime.now().toString();

        long result = redisStockService.issueAtomically(
                request.code(),
                String.valueOf(request.userId()),
                ticketId,
                request.requestIp() != null ? request.requestIp() : "",
                requestTime
        );

        if (result == -3) {
            throw new DuplicateCouponException("이미 발급된 쿠폰입니다.");
        }
        if (result == -4) {
            log.info("[{}]: 발급 처리 중 중복 요청 | userId: {}", request.code(), request.userId());
            return TicketResponse.builder()
                    .status(TicketStatus.PENDING)
                    .message("발급 처리 중입니다.")
                    .build();
        }
        if (result == -2) {
            throw new CouponNotFoundException("존재하지 않는 쿠폰입니다.");
        }
        if (result == -1) {
            throw new CouponOutOfStockException("쿠폰이 모두 소진되었습니다.");
        }

        TicketResponse ticket = TicketResponse.pending(ticketId);
        redisTicketService.saveTicket(ticketId, ticket);

        log.info("[{}]: 발급 접수 완료 | userId: {} | ticketId: {} | 남은재고: {}",
                request.code(), request.userId(), ticketId, result);

        return ticket;
    }
}
