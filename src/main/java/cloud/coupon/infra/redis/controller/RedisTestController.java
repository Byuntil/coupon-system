package cloud.coupon.infra.redis.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RedisTestController {

    private final RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/redis-test")
    public String redisTest() {
        // Redis에 데이터 저장
        ValueOperations<String, Object> ops = redisTemplate.opsForValue();
        ops.set("test:string", "Hello Redis!");

        // 저장한 데이터 조회
        String result = (String) ops.get("test:string");

        return result;
    }
}
