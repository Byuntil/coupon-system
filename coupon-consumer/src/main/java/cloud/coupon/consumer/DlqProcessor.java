package cloud.coupon.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqProcessor {

    public void moveToDlq(MapRecord<String, String, String> record, String errorMessage) {
        // Task 6에서 구현 예정
        log.warn("DLQ 이동 (미구현): messageId={}, error={}", record.getId().getValue(), errorMessage);
    }
}
