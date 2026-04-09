package cloud.coupon.infra.redis.service;

import cloud.coupon.domain.coupon.dto.response.TicketResponse;
import cloud.coupon.domain.coupon.dto.response.TicketStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisTicketService {

    private static final String TICKET_KEY_PREFIX = "coupon:ticket:";
    private static final String RESULT_CHANNEL_PREFIX = "coupon:result:";
    private static final Duration TICKET_TTL = Duration.ofMinutes(5);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public void saveTicket(String ticketId, TicketResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(TICKET_KEY_PREFIX + ticketId, json, TICKET_TTL);
        } catch (JsonProcessingException e) {
            log.error("ticket 직렬화 실패: ticketId={}", ticketId, e);
        }
    }

    public Optional<TicketResponse> getTicket(String ticketId) {
        String json = redisTemplate.opsForValue().get(TICKET_KEY_PREFIX + ticketId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, TicketResponse.class));
        } catch (JsonProcessingException e) {
            log.error("ticket 역직렬화 실패: ticketId={}", ticketId, e);
            return Optional.empty();
        }
    }

    public void publishResult(String ticketId, TicketStatus status) {
        redisTemplate.convertAndSend(RESULT_CHANNEL_PREFIX + ticketId, status.name());
    }

    public String getResultChannelName(String ticketId) {
        return RESULT_CHANNEL_PREFIX + ticketId;
    }
}
