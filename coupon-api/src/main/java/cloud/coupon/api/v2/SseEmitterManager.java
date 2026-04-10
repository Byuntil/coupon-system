package cloud.coupon.api.v2;

import cloud.coupon.domain.coupon.dto.response.TicketResponse;
import cloud.coupon.domain.coupon.dto.response.TicketStatus;
import cloud.coupon.infra.redis.service.RedisTicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
        Optional<TicketResponse> ticket = redisTicketService.getTicket(ticketId);

        // 1. 존재하지 않는 ticket 처리
        if (ticket.isEmpty()) {
            return createErrorEmitter("존재하지 않는 ticketId입니다.");
        }

        // 2. 이미 완료된 경우(PENDING이 아닌 경우) 즉시 반환
        if (ticket.get().getStatus() != TicketStatus.PENDING) {
            return createStatusEmitter(ticket.get());
        }

        // 3. PENDING: Pub/Sub 리스너 등록 (Race Condition 방지)
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitters.put(ticketId, emitter);

        String channel = redisTicketService.getResultChannelName(ticketId);
        MessageListener listener = (message, pattern) -> handleMessage(ticketId);

        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));

        // 4. 리스너 등록 후 재확인 — 등록 전에 완료된 케이스 처리
        Optional<TicketResponse> recheck = redisTicketService.getTicket(ticketId);
        if (recheck.isPresent() && recheck.get().getStatus() != TicketStatus.PENDING) {
            sendTicketStatus(emitter, recheck.get());
            cleanup(ticketId, listener, channel);
            return emitter;
        }

        // 5. 콜백 설정
        emitter.onCompletion(() -> cleanup(ticketId, listener, channel));
        emitter.onTimeout(() -> {
            sendTimeout(ticketId, emitter);
            cleanup(ticketId, listener, channel);
        });
        emitter.onError(e -> cleanup(ticketId, listener, channel));

        return emitter;
    }

    private SseEmitter createErrorEmitter(String message) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        sendError(emitter, message);
        return emitter;
    }

    private SseEmitter createStatusEmitter(TicketResponse ticket) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        sendTicketStatus(emitter, ticket);
        return emitter;
    }

    private void handleMessage(String ticketId) {
        SseEmitter emitter = emitters.get(ticketId);
        if (emitter == null) return;

        redisTicketService.getTicket(ticketId)
                .ifPresentOrElse(
                        ticket -> sendTicketStatus(emitter, ticket),
                        () -> sendError(emitter, "결과를 가져올 수 없습니다. /status API를 사용해주세요.")
                );
    }

    private void sendTicketStatus(SseEmitter emitter, TicketResponse ticket) {
        try {
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data(objectMapper.writeValueAsString(ticket)));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data("{\"message\":\"" + message + "\"}"));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendTimeout(String ticketId, SseEmitter emitter) {
        TicketResponse timeout = TicketResponse.builder()
                .ticketId(ticketId)
                .status(TicketStatus.TIMEOUT)
                .message("처리 시간이 초과되었습니다. /status API로 결과를 확인해주세요.")
                .build();
        sendTicketStatus(emitter, timeout);
    }

    private void cleanup(String ticketId, MessageListener listener, String channel) {
        emitters.remove(ticketId);
        listenerContainer.removeMessageListener(listener, new ChannelTopic(channel));
    }
}
