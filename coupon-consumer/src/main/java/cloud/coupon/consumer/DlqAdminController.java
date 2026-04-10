package cloud.coupon.consumer;

import cloud.coupon.consumer.config.ConsumerProperties;
import cloud.coupon.infra.redis.service.RedisStreamService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// TODO: 운영 환경에서는 Spring Security 또는 IP 화이트리스트로 접근 제한 필요.
@RestController
@RequestMapping("/admin/dlq")
@RequiredArgsConstructor
public class DlqAdminController {

    private final ConsumerProperties consumerProperties;
    private final RedisStreamService redisStreamService;

    @GetMapping
    public ResponseEntity<List<Map<String, String>>> listDlq(
            @RequestParam(defaultValue = "100") long count) {
        List<MapRecord<String, String, String>> records = redisStreamService.readDlq(consumerProperties.getDlqKey(), count);
        List<Map<String, String>> result = records.stream()
                .map(record -> {
                    Map<String, String> entry = new HashMap<>(record.getValue());
                    entry.put("messageId", record.getId().getValue());
                    return entry;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }
}
