package cloud.coupon.infra.redis.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedisStreamServiceTest {

    @Test
    void detectsBusyGroupInNestedCause() {
        RuntimeException exception = new RuntimeException(
                "Error in execution",
                new RuntimeException("BUSYGROUP Consumer Group name already exists")
        );

        assertThat(RedisStreamService.isBusyGroupError(exception)).isTrue();
    }

    @Test
    void ignoresOtherRedisErrors() {
        RuntimeException exception = new RuntimeException(
                "Error in execution",
                new RuntimeException("NOGROUP No such key")
        );

        assertThat(RedisStreamService.isBusyGroupError(exception)).isFalse();
    }
}
