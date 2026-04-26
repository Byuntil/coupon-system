package cloud.coupon.infra.redis.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStreamService {

    private final RedisTemplate<String, String> redisTemplate;

    public void createGroupIfNotExists(String streamKey, String groupName) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);
            log.info("Consumer Group 생성: stream={}, group={}", streamKey, groupName);
        } catch (Exception e) {
            if (isBusyGroupError(e)) {
                log.debug("Consumer Group 이미 존재: stream={}, group={}", streamKey, groupName);
            } else {
                throw e;
            }
        }
    }

    static boolean isBusyGroupError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<MapRecord<String, String, String>> readGroup(
            String streamKey, String groupName, String consumerName,
            int count, Duration blockTimeout) {
        StreamReadOptions options = StreamReadOptions.empty()
                .count(count)
                .block(blockTimeout);

        List records = redisTemplate.opsForStream().read(
                Consumer.from(groupName, consumerName),
                options,
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );
        return (List<MapRecord<String, String, String>>) records;
    }

    public Long acknowledge(String streamKey, String groupName, RecordId recordId) {
        return redisTemplate.opsForStream().acknowledge(streamKey, groupName, recordId);
    }

    public PendingMessages pending(String streamKey, String groupName, long count) {
        return redisTemplate.opsForStream().pending(streamKey, groupName,
                org.springframework.data.domain.Range.unbounded(), count);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<MapRecord<String, String, String>> claim(
            String streamKey, String groupName, String consumerName,
            Duration minIdleTime, RecordId... recordIds) {
        List records = redisTemplate.opsForStream().claim(
                streamKey, groupName, consumerName, minIdleTime, recordIds
        );
        return (List<MapRecord<String, String, String>>) records;
    }

    public RecordId addToDlq(String dlqKey, Map<String, String> fields) {
        MapRecord<String, String, String> record = MapRecord.create(dlqKey, fields);
        return redisTemplate.opsForStream().add(record);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<MapRecord<String, String, String>> readDlq(String dlqKey, long count) {
        List records = redisTemplate.opsForStream().range(dlqKey,
                org.springframework.data.domain.Range.unbounded(),
                org.springframework.data.redis.connection.Limit.limit().count((int) count));
        return (List<MapRecord<String, String, String>>) records;
    }
}
