package cloud.coupon.api.v2;

import cloud.coupon.domain.coupon.dto.response.TicketResponse;
import cloud.coupon.domain.coupon.dto.response.TicketStatus;
import cloud.coupon.infra.redis.service.RedisTicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class SseEmitterManager {

    private static final long SSE_TIMEOUT = 30_000L;
    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final RedisTicketService redisTicketService;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;

    public SseEmitterManager(RedisTicketService redisTicketService,
                             RedisMessageListenerContainer listenerContainer,
                             ObjectMapper objectMapper) {
        this.redisTicketService = redisTicketService;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(String ticketId) {
        // ticket 자체가 없으면 즉시 404 에러 응답
        Optional<TicketResponse> existing = redisTicketService.getTicket(ticketId);
        if (existing.isEmpty()) {
            SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"message\":\"존재하지 않는 ticketId입니다.\"}"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // 이미 완료된 ticket이면 즉시 전송 후 종료
        if (existing.isPresent() && existing.get().getStatus() != TicketStatus.PENDING) {
            SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(objectMapper.writeValueAsString(existing.get())));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // PENDING이면 Redis Pub/Sub 구독
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitters.put(ticketId, emitter);

        String channel = redisTicketService.getResultChannelName(ticketId);
        MessageListener listener = (message, pattern) -> handleMessage(ticketId, message);

        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));

        emitter.onCompletion(() -> cleanup(ticketId, listener, channel));
        emitter.onTimeout(() -> {
            sendTimeout(ticketId, emitter);
            cleanup(ticketId, listener, channel);
        });
        emitter.onError(e -> cleanup(ticketId, listener, channel));

        return emitter;
    }

    private void handleMessage(String ticketId, Message message) {
        SseEmitter emitter = emitters.get(ticketId);
        if (emitter == null) return;

        Optional<TicketResponse> ticket = redisTicketService.getTicket(ticketId);
        if (ticket.isPresent()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(objectMapper.writeValueAsString(ticket.get())));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        } else {
            // ticket 데이터를 가져올 수 없는 경우 에러 이벤트 전송 후 cleanup
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"message\":\"결과를 가져올 수 없습니다. /status API를 사용해주세요.\"}"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }
    }

    private void sendTimeout(String ticketId, SseEmitter emitter) {
        try {
            TicketResponse timeout = TicketResponse.builder()
                    .ticketId(ticketId)
                    .status(TicketStatus.TIMEOUT)
                    .message("처리 시간이 초과되었습니다. /status API로 결과를 확인해주세요.")
                    .build();
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data(objectMapper.writeValueAsString(timeout)));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void cleanup(String ticketId, MessageListener listener, String channel) {
        emitters.remove(ticketId);
        listenerContainer.removeMessageListener(listener, new ChannelTopic(channel));
    }
}
