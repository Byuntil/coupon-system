package cloud.coupon.consumer;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.dto.response.CouponIssueResult;
import cloud.coupon.domain.coupon.dto.response.TicketResponse;
import cloud.coupon.domain.coupon.dto.response.TicketStatus;
import cloud.coupon.domain.coupon.service.CouponIssuancePersistenceService;
import cloud.coupon.infra.redis.service.RedisStockService;
import cloud.coupon.infra.redis.service.RedisTicketService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssuanceProcessor {

    private final CouponIssuancePersistenceService persistenceService;
    private final RedisStockService redisStockService;
    private final RedisTicketService redisTicketService;

    /**
     * Stream 메시지를 처리: DB 트랜잭션 + Redis 상태 전이.
     * 반환값: true(성공/비즈니스 실패 처리 완료), false(재시도 필요)
     */
    public boolean process(Map<String, String> fields) {
        String ticketId = fields.get("ticketId");
        String code = fields.get("code");
        String userId = fields.get("userId");
        String requestIp = fields.getOrDefault("requestIp", "");

        try {
            CouponIssueRequest request = new CouponIssueRequest(code, Long.parseLong(userId), requestIp);
            CouponIssueResult result = persistenceService.issueReservedCoupon(request);

            redisStockService.transitionToIssued(code, userId);
            TicketResponse response = TicketResponse.completed(ticketId, result.getCouponCode());
            redisTicketService.saveTicket(ticketId, response);
            redisTicketService.publishResult(ticketId, TicketStatus.COMPLETED);

            log.info("[{}]: 발급 완료 | userId: {} | ticketId: {} | issuedCode: {}",
                    code, userId, ticketId, result.getCouponCode());
            return true;

        } catch (DataIntegrityViolationException e) {
            // ACK-loss 재처리: DB 커밋 성공 후 ACK 전 크래시로 인한 재처리.
            redisStockService.transitionToIssued(code, userId);

            TicketResponse response = TicketResponse.completed(ticketId, code);
            redisTicketService.saveTicket(ticketId, response);
            redisTicketService.publishResult(ticketId, TicketStatus.COMPLETED);

            log.info("[{}]: ACK-loss 재처리 완료 | userId: {} | ticketId: {}",
                    code, userId, ticketId);
            return true;

        } catch (Exception e) {
            log.error("[{}]: 발급 처리 실패 | userId: {} | ticketId: {} | 원인: {}",
                    code, userId, ticketId, e.getMessage());
            return false;
        }
    }
}
