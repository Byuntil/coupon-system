package cloud.coupon.api.v2;

import static org.assertj.core.api.Assertions.assertThat;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponType;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.infra.redis.service.RedisStockService;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 비동기 쿠폰 발급 정합성 테스트.
 *
 * <p>실행 전제조건: 로컬 Redis(localhost:6379)가 구동 중이어야 합니다.
 * H2 인메모리 DB는 application-local.yml 설정을 따릅니다.
 *
 * <p>검증 목표:
 * Redis Lua 스크립트(issueAtomically)가 동시 요청 환경에서 정확히 재고 수만큼만
 * 202 Accepted를 반환하는지 원자성을 검증합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncCouponConsistencyTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponIssueRepository couponIssueRepository;
    @Autowired private RedisStockService redisStockService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    private static final String COUPON_CODE = "CONSISTENCY_TEST_001";
    private static final int TOTAL_STOCK = 100;
    private static final int CONCURRENT_USERS = 1000;

    @BeforeEach
    void setUp() {
        couponIssueRepository.deleteAll();
        couponRepository.findByCodeAndIsDeletedFalse(COUPON_CODE)
                .ifPresent(c -> couponRepository.delete(c));

        // Redis 관련 키 전체 정리
        Set<String> keys = redisTemplate.keys("coupon:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private void createCoupon(int stock) {
        Coupon coupon = Coupon.builder()
                .code(COUPON_CODE)
                .name("정합성 테스트 쿠폰")
                .totalStock(stock)
                .type(CouponType.FIXED_AMOUNT)
                .discountValue(1000)
                .startTime(LocalDateTime.now().minusDays(1))
                .endTime(LocalDateTime.now().plusDays(1))
                .expireTime(LocalDateTime.now().plusDays(30))
                .build();
        couponRepository.save(coupon);
        redisStockService.initializeStock(COUPON_CODE, stock);
    }

    @Test
    @DisplayName("동시 1,000명 요청 (재고 100) → Redis 레벨에서 정확히 100명만 202 수락")
    void concurrentIssue_redisConsistencyCheck() throws InterruptedException {
        createCoupon(TOTAL_STOCK);

        ExecutorService executor = Executors.newFixedThreadPool(100);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_USERS);
        AtomicInteger acceptedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            final long userId = i + 1;
            executor.submit(() -> {
                try {
                    CouponIssueRequest request = new CouponIssueRequest(COUPON_CODE, userId, "127.0.0.1");
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            "/api/v2/coupons/issue", request, String.class);
                    if (response.getStatusCode() == HttpStatus.ACCEPTED) {
                        acceptedCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // 4xx/5xx 응답을 예외로 처리하는 경우에도 거부로 카운팅
                    rejectedCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Redis Lua 스크립트가 정확히 재고 수만큼만 202 수락해야 함
        assertThat(acceptedCount.get())
                .as("수락된 요청 수가 재고(100)와 정확히 일치해야 함")
                .isEqualTo(TOTAL_STOCK);
        assertThat(rejectedCount.get())
                .as("거부된 요청 수가 나머지(900)와 정확히 일치해야 함")
                .isEqualTo(CONCURRENT_USERS - TOTAL_STOCK);

        // Redis 재고 키가 0이어야 함
        String stockValue = redisTemplate.opsForValue().get("coupon:stock:" + COUPON_CODE);
        assertThat(stockValue)
                .as("Redis 재고가 0이어야 함")
                .isEqualTo("0");
    }
}
