package cloud.coupon.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponType;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.infra.redis.service.RedisStockService;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CouponServicePerformanceTest {
    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    @Autowired
    private RedisStockService redisStockService;

    private String code;
    private final String requestIp = "127.0.0.1";
    private static final int TOTAL_THREAD_COUNT = 10000;
    private static final int THREAD_POOL_SIZE = 32;
    private static final int STOCK_COUNT = 100000;

    @BeforeEach
    void setUp() {
        // 기존 데이터 정리
        couponRepository.deleteAll();
        couponIssueRepository.deleteAll();
        redisStockService.deleteAllKeys(); // 모든 Redis 키를 삭제하는 것이 더 안전합니다

        // 테스트용 쿠폰 생성 및 초기화
        Coupon coupon = createTestCoupon(STOCK_COUNT);
        code = couponRepository.save(coupon).getCode();

        // Redis 재고 초기화 전에 초기값 검증
        assertThat(coupon.getRemainStock()).isEqualTo(STOCK_COUNT);
        redisStockService.syncStockWithDB(code, coupon.getRemainStock());
    }

    @Test
    void performanceTest() throws InterruptedException {
        // given
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch latch = new CountDownLatch(TOTAL_THREAD_COUNT);

        // when
        for (int i = 0; i < TOTAL_THREAD_COUNT; i++) {
            final long userId = i; // 변수 캡처를 위해 final로 선언
            executorService.submit(() -> {
                try {
                    CouponIssueRequest request = new CouponIssueRequest(
                            code,
                            userId,
                            requestIp);
                    couponService.issueCoupon(request);
                } catch (Exception e) {
                    // 예외 발생 시 로깅
                    System.err.println("Error processing request: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // then
        latch.await();
        executorService.shutdown(); // 스레드 풀 정리

        // 결과 검증
        Coupon updatedCoupon = couponRepository.findByCode(code).orElseThrow();
        int issuedCount = couponIssueRepository.countByCouponCode(code);

        assertThat(updatedCoupon.getRemainStock()).isEqualTo(STOCK_COUNT - TOTAL_THREAD_COUNT);

        assertThat(issuedCount).isEqualTo(TOTAL_THREAD_COUNT);
    }

    private Coupon createTestCoupon(int stock) {
        return Coupon.builder()
                .name("테스트 쿠폰")
                .code("TEST-" + UUID.randomUUID().toString().substring(0, 8))
                .totalStock(stock)
                .type(CouponType.FIXED_AMOUNT)
                .discountValue(1000)
                .startTime(LocalDateTime.now().minusDays(1))
                .endTime(LocalDateTime.now().plusDays(1))
                .expireTime(LocalDateTime.now().plusDays(30))
                .build();
    }
}
