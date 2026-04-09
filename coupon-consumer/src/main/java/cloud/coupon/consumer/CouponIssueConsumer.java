package cloud.coupon.consumer;

import cloud.coupon.consumer.config.ConsumerProperties;
import cloud.coupon.infra.redis.service.RedisStreamService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueConsumer {

    private final ConsumerProperties properties;
    private final RedisStreamService redisStreamService;
    private final CouponIssuanceProcessor processor;
    private final DlqProcessor dlqProcessor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private final ConcurrentHashMap<String, Integer> retryCountMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() {
        redisStreamService.createGroupIfNotExists(properties.getStreamKey(), properties.getGroupName());
        running.set(true);
        executorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "coupon-issue-consumer");
            t.setDaemon(true);
            return t;
        });
        executorService.submit(this::consumeLoop);
        log.info("CouponIssueConsumer 시작: stream={}, group={}, consumer={}",
                properties.getStreamKey(), properties.getGroupName(), properties.getConsumerName());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(properties.getBlockTimeout() + 500, TimeUnit.MILLISECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("CouponIssueConsumer 종료");
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                List<MapRecord<String, String, String>> records = redisStreamService.readGroup(
                        properties.getStreamKey(),
                        properties.getGroupName(),
                        properties.getConsumerName(),
                        properties.getBatchSize(),
                        Duration.ofMillis(properties.getBlockTimeout())
                );

                if (records == null || records.isEmpty()) {
                    continue;
                }

                for (MapRecord<String, String, String> record : records) {
                    processRecord(record);
                }
            } catch (Exception e) {
                log.error("Consumer 루프 에러: {}", e.getMessage(), e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void processRecord(MapRecord<String, String, String> record) {
        String messageId = record.getId().getValue();
        Map<String, String> fields = record.getValue();

        boolean success = processor.process(fields);

        if (success) {
            redisStreamService.acknowledge(
                    properties.getStreamKey(), properties.getGroupName(), record.getId());
            retryCountMap.remove(messageId);
        } else {
            int retryCount = retryCountMap.merge(messageId, 1, Integer::sum);
            if (retryCount >= properties.getMaxRetry()) {
                dlqProcessor.moveToDlq(record, "최대 재시도 초과");
                redisStreamService.acknowledge(
                        properties.getStreamKey(), properties.getGroupName(), record.getId());
                retryCountMap.remove(messageId);
            }
        }
    }
}
