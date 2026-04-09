package cloud.coupon.consumer;

import cloud.coupon.consumer.config.ConsumerProperties;
import cloud.coupon.infra.redis.service.RedisStreamService;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingMessageRecovery {

    private final ConsumerProperties properties;
    private final RedisStreamService redisStreamService;
    private final CouponIssuanceProcessor processor;
    private final DlqProcessor dlqProcessor;

    @Scheduled(fixedDelayString = "${coupon.consumer.claim-interval:30000}")
    public void recoverPendingMessages() {
        try {
            PendingMessages pendingMessages = redisStreamService.pending(
                    properties.getStreamKey(), properties.getGroupName(), 100);

            if (pendingMessages.isEmpty()) return;

            Duration minIdleTime = Duration.ofMillis(properties.getClaimIdleTime());

            for (PendingMessage pending : pendingMessages) {
                if (pending.getElapsedTimeSinceLastDelivery().compareTo(minIdleTime) < 0) {
                    continue;
                }

                RecordId recordId = pending.getId();
                long deliveryCount = pending.getTotalDeliveryCount();

                List<MapRecord<String, String, String>> claimed = redisStreamService.claim(
                        properties.getStreamKey(),
                        properties.getGroupName(),
                        properties.getConsumerName(),
                        minIdleTime,
                        recordId
                );

                for (MapRecord<String, String, String> record : claimed) {
                    if (deliveryCount >= properties.getMaxRetry()) {
                        dlqProcessor.moveToDlq(record,
                                "PEL 복구 — 최대 재시도 초과 (deliveryCount=" + deliveryCount + ")");
                        redisStreamService.acknowledge(
                                properties.getStreamKey(), properties.getGroupName(), record.getId());
                    } else {
                        boolean success = processor.process(record.getValue());
                        if (success) {
                            redisStreamService.acknowledge(
                                    properties.getStreamKey(), properties.getGroupName(), record.getId());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("PendingMessageRecovery 에러: {}", e.getMessage(), e);
        }
    }
}
