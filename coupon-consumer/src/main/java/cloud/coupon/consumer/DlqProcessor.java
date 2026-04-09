package cloud.coupon.consumer;

import cloud.coupon.domain.coupon.dto.response.TicketResponse;
import cloud.coupon.domain.coupon.dto.response.TicketStatus;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.infra.redis.service.RedisStockService;
import cloud.coupon.infra.redis.service.RedisStreamService;
import cloud.coupon.infra.redis.service.RedisTicketService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqProcessor {

    private static final String DLQ_KEY = "coupon:issue:dlq";

    private final CouponIssueRepository couponIssueRepository;
    private final RedisStockService redisStockService;
    private final RedisStreamService redisStreamService;
    private final RedisTicketService redisTicketService;

    /**
     * DLQ 이동 전 DB 조회로 실패 유형 구분.
     * "실패처럼 보였지만 DB는 성공"인 케이스를 처리.
     */
    public void moveToDlq(MapRecord<String, String, String> record, String errorMessage) {
        Map<String, String> fields = record.getValue();
        String ticketId = fields.get("ticketId");
        String code = fields.get("code");
        String userId = fields.get("userId");

        // DB 조회: 이미 발급되었는가?
        boolean alreadyIssued = couponIssueRepository.existsByCouponCodeAndUserId(
                code, Long.parseLong(userId));

        if (alreadyIssued) {
            // 케이스 2-A: DB 커밋 성공 후 ACK 전 장애
            log.info("[{}]: DLQ 이동 취소 — DB에 이미 발급됨 | userId: {} | ticketId: {}",
                    code, userId, ticketId);
            redisStockService.transitionToIssued(code, userId);
            TicketResponse response = TicketResponse.completed(ticketId, "DB에서 확인됨");
            redisTicketService.saveTicket(ticketId, response);
            redisTicketService.publishResult(ticketId, TicketStatus.COMPLETED);
        } else {
            // 케이스 2-B: 확실한 미발급
            log.warn("[{}]: DLQ 이동 | userId: {} | ticketId: {} | 원인: {}",
                    code, userId, ticketId, errorMessage);
            redisStockService.rollbackInflight(code, userId);

            TicketResponse response = TicketResponse.failed(ticketId, errorMessage);
            redisTicketService.saveTicket(ticketId, response);
            redisTicketService.publishResult(ticketId, TicketStatus.FAILED);

            Map<String, String> dlqFields = new HashMap<>(fields);
            dlqFields.put("errorMessage", errorMessage);
            dlqFields.put("failedAt", LocalDateTime.now().toString());
            dlqFields.put("originalMessageId", record.getId().getValue());
            redisStreamService.addToDlq(DLQ_KEY, dlqFields);
        }
    }
}
