package cloud.coupon.domain.coupon.service;

import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_DUPLICATE_ERROR_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_ISSUE_NOT_FOUND_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_NOT_FOUND_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.dto.response.CouponIssueResult;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponIssue;
import cloud.coupon.domain.coupon.entity.CouponIssueStatus;
import cloud.coupon.domain.coupon.entity.IssueResult;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.domain.history.entity.CouponIssueHistory;
import cloud.coupon.domain.history.repository.CouponIssueHistoryRepository;
import cloud.coupon.domain.coupon.service.strategy.CouponIssuanceStrategy;
import cloud.coupon.domain.coupon.service.strategy.RedisCouponIssuanceStrategy;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import cloud.coupon.global.error.exception.coupon.DuplicateCouponException;
import cloud.coupon.global.error.exception.couponissue.CouponIssueNotFoundException;
import cloud.coupon.infra.redis.service.RedisStockService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class CouponServiceTest {
    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    @Autowired
    private CouponIssueHistoryRepository couponIssueHistoryRepository;

    @Autowired
    private CouponIssuanceStrategy issuanceStrategy;

    @Autowired
    private RedisStockService redisStockService;

    private String code;
    private final Long userId = 1L;
    private final String requestIp = "127.0.0.1";

    private boolean isRedisStrategy() {
        return issuanceStrategy instanceof RedisCouponIssuanceStrategy;
    }

    @AfterEach
    void tearDown() {
        couponIssueHistoryRepository.deleteAll();
        couponIssueRepository.deleteAll();
        couponRepository.deleteAll();
        if (isRedisStrategy()) {
            redisStockService.deleteAllKeys();
        }
    }

    @BeforeEach
    void setUp() {
        couponIssueHistoryRepository.deleteAll();
        couponIssueRepository.deleteAll();
        couponRepository.deleteAll();

        if (isRedisStrategy()) {
            redisStockService.deleteAllKeys();
        }

        // 테스트용 쿠폰 생성
        Coupon coupon = Coupon.builder()
                .name("테스트 쿠폰")
                .code("TEST-0001")
                .totalStock(10)
                .startTime(LocalDateTime.now().minusDays(1))
                .endTime(LocalDateTime.now().plusDays(1))
                .expireTime(LocalDateTime.now().plusDays(30))
                .build();

        code = couponRepository.save(coupon).getCode();

        if (isRedisStrategy()) {
            redisStockService.syncStockWithDB(code, coupon.getRemainStock());
        }
    }

    @Test
    @Transactional
    @DisplayName("쿠폰 발급 성공")
    void issueCoupon_success() {
        // when
        CouponIssueResult result = couponService.issueCoupon(new CouponIssueRequest(code, userId, requestIp));

        // then
        assertThat(result.isSuccess()).isTrue();

        // DB 확인
        CouponIssue savedCouponIssue = couponIssueRepository.findByIssuedCodeAndUserId(result.getCouponCode(), userId)
                .orElseThrow();
        assertThat(savedCouponIssue.getUserId()).isEqualTo(userId);

        Coupon updatedCoupon = couponRepository.findByCodeAndIsDeletedFalse(code)
                .orElseThrow();
        assertThat(updatedCoupon.getRemainStock()).isEqualTo(9);

        // 히스토리 확인
        CouponIssueHistory history = couponIssueHistoryRepository
                .findByCodeAndUserId(code, userId)
                .orElseThrow();
        assertThat(history.getResult()).isEqualTo(IssueResult.SUCCESS);
    }

    @Test
    @DisplayName("발급 로그에 성공률 계측 문구를 남기지 않는다")
    void issueCoupon_logDoesNotContainRateMetrics(CapturedOutput output) {
        // when
        couponService.issueCoupon(new CouponIssueRequest(code, userId, requestIp));

        // then
        assertThat(output.getOut()).doesNotContain("발급 비율");
        assertThat(output.getOut()).doesNotContain("시스템 정상 처리율");
    }

    @Test
    @DisplayName("이미 발급받은 쿠폰을 재발급 시도시 예외 발생")
    void issueCoupon_duplicateIssue() {
        // given
        couponService.issueCoupon(new CouponIssueRequest(code, userId, requestIp));

        // when & then
        assertThatThrownBy(() -> couponService.issueCoupon(
                new CouponIssueRequest(code, userId, requestIp)))
                .isInstanceOf(DuplicateCouponException.class)
                .hasMessage(COUPON_DUPLICATE_ERROR_MESSAGE);
    }

    @Test
    @DisplayName("존재하지 않는 쿠폰으로 발급 시도시 예외 발생")
    void issueCoupon_notFoundCoupon() {
        // given
        String nonExistentCouponId = "NON-EXISTENT-COUPON-ID";

        // when & then
        assertThatThrownBy(() ->
                couponService.issueCoupon(new CouponIssueRequest(nonExistentCouponId, userId, requestIp)))
                .isInstanceOf(CouponNotFoundException.class)
                .hasMessage(COUPON_NOT_FOUND_MESSAGE);
    }

    @Test
    @DisplayName("재고가 없는 쿠폰 발급 시도시 실패")
    void issueCoupon_outOfStock() {
        // given
        // 재고 소진
        for (int i = 0; i < 10; i++) {
            couponService.issueCoupon(new CouponIssueRequest(code, userId + i, requestIp));
        }

        // when
        CouponIssueResult result = couponService.issueCoupon(new CouponIssueRequest(code, userId + 10, requestIp));

        // then
        assertThat(result.isSuccess()).isFalse();

        // 히스토리 확인
        CouponIssueHistory history = couponIssueHistoryRepository
                .findByCodeAndUserId(code, userId + 10)
                .orElseThrow();
        assertThat(history.getResult()).isEqualTo(IssueResult.FAIL);
    }

    @Test
    @Transactional
    @DisplayName("쿠폰 사용 성공")
    void useCoupon_success() {
        // given
        CouponIssueResult couponIssueResult = couponService.issueCoupon(
                new CouponIssueRequest(code, userId, requestIp));// 쿠폰 발급

        // when
        couponService.useCoupon(userId, couponIssueResult.getCouponCode());

        // then
        CouponIssue couponIssue = couponIssueRepository.findByIssuedCodeAndUserId(couponIssueResult.getCouponCode(),
                        userId)
                .orElseThrow();
        assertThat(couponIssue.isUsed()).isTrue();
        assertThat(couponIssue.getStatus()).isEqualTo(CouponIssueStatus.USED);
    }

    @Test
    @DisplayName("존재하지 않는 쿠폰 사용 시도시 예외 발생")
    void useCoupon_couponIssueNotFound() {
        // when & then
        assertThatThrownBy(() ->
                couponService.useCoupon(userId, "INVALID_CODE"))
                .isInstanceOf(CouponIssueNotFoundException.class)
                .hasMessageContaining(COUPON_ISSUE_NOT_FOUND_MESSAGE);
    }

    @Test
    @DisplayName("동일 유저 동시 재요청 시 발급은 1건만 저장되고 Redis 재고는 보상 복구된다")
    void issueCoupon_duplicateConcurrentRequest_compensatesRedisStock() throws Exception {
        // 현재 구현에서는 이 테스트가 실패할 수 있습니다 (Redis 보상 복구 미구현)
        // Redis 전략에서만 의미 있는 테스트
        if (!isRedisStrategy()) {
            return;
        }

        // given - same user, two concurrent requests
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        // when
        for (int i = 0; i < 2; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await(); // 동시에 시작
                    couponService.issueCoupon(new CouponIssueRequest(code, userId, requestIp));
                } catch (Exception e) {
                    // 중복 발급 예외 등은 정상 흐름
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 두 스레드 동시 출발
        doneLatch.await();
        executorService.shutdown();

        // then: CouponIssue는 1건만 남아야 함
        int issuedCount = couponIssueRepository.countByCouponCode(code);
        assertThat(issuedCount).isEqualTo(1);

        // then: Redis 재고는 1개만 감소해야 함 (remainStock == 9)
        Coupon updatedCoupon = couponRepository.findByCodeAndIsDeletedFalse(code).orElseThrow();
        assertThat(updatedCoupon.getRemainStock()).isEqualTo(9);

        // then: history는 성공 1건, duplicate 실패 1건
        List<CouponIssueHistory> histories = couponIssueHistoryRepository.findAll().stream()
                .filter(h -> h.getCode().equals(code) && userId.equals(h.getUserId()))
                .toList();
        long successCount = histories.stream().filter(h -> h.getResult() == IssueResult.SUCCESS).count();
        long failCount = histories.stream().filter(h -> h.getResult() == IssueResult.FAIL).count();

        assertThat(successCount).isEqualTo(1);
        assertThat(failCount).isEqualTo(1);
    }
}
