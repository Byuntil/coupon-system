package cloud.coupon.consumer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "coupon.consumer")
public class ConsumerProperties {
    private String streamKey = "coupon:issue:stream";
    private String groupName = "coupon-issue-group";
    private String consumerName = "consumer-default";
    private String dlqKey = "coupon:issue:dlq";
    private int batchSize = 10;
    private long blockTimeout = 2000;
    private int maxRetry = 3;
    private long claimIdleTime = 60000;
    private long claimInterval = 30000;
}
