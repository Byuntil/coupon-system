package cloud.coupon.api.v2;

import static org.assertj.core.api.Assertions.assertThat;

import cloud.coupon.domain.coupon.dto.request.CouponIssueRequest;
import cloud.coupon.domain.coupon.dto.response.TicketResponse;
import cloud.coupon.domain.coupon.dto.response.TicketStatus;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponType;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.infra.redis.service.RedisStockService;
import cloud.coupon.infra.redis.service.RedisTicketService;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 비동기 쿠폰 발급 v2 API 통합 테스트.
 *
 * <p>실행 전제조건: 로컬 Redis(localhost:6379)가 구동 중이어야 합니다.
 * H2 인메모리 DB는 application-local.yml 설정을 따릅니다.
 *
 * <p>Consumer 부재 주의:
 * coupon-consumer 모듈의 {@code CouponIssueConsumer}는 이 테스트 컨텍스트에서 실행되지 않습니다.
 * 따라서 Redis Stream에 enqueue된 메시지를 소비하는 주체가 없어,
 * ticketId 상태는 PENDING에서 COMPLETED로 전이되지 않습니다.
 * polling 결과 조회 시나리오는 @Disabled 처리되었습니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncCouponIssueIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponIssueRepository couponIssueRepository;
    @Autowired private RedisStockService redisStockService;
    @Autowired private RedisTicketService redisTicketService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    private static final String COUPON_CODE = "TEST_ASYNC_001";

    @BeforeEach
    void setUp() {
        // DB 정리
        couponIssueRepository.deleteAll();
        couponRepository.findByCodeAndIsDeletedFalse(COUPON_CODE)
                .ifPresent(c -> couponRepository.delete(c));

        // Redis 전체 정리 (stock, inflight, issued, ticket 키 모두 삭제)
        deleteRedisKeysByPattern("coupon:*");
    }

    private void deleteRedisKeysByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private void createCoupon(int stock) {
        Coupon coupon = Coupon.builder()
                .code(COUPON_CODE)
                .name("비동기 테스트 쿠폰")
                .totalStock(stock)
                .type(CouponType.FIXED_AMOUNT)
                .discountValue(1000)
                .startTime(LocalDateTime.now().minusDays(1))
                .endTime(LocalDateTime.now().plusDays(1))
                .expireTime(LocalDateTime.now().plusDays(30))
                .build();
        couponRepository.save(coupon);
        // 재고가 0이어도 Redis 키는 초기화해야 Lua 스크립트에서 -1(재고소진)을 반환함.
        // 키가 없으면 -2(쿠폰없음)로 처리됨.
        redisStockService.initializeStock(COUPON_CODE, stock);
    }

    @Test
    @DisplayName("정상 발급 접수 → 202 Accepted + ticketId 반환")
    void issueCoupon_accepted() {
        // given
        createCoupon(10);
        CouponIssueRequest request = new CouponIssueRequest(COUPON_CODE, 1L, "127.0.0.1");

        // when
        ResponseEntity<TicketResponse> response = restTemplate.postForEntity(
                "/api/v2/coupons/issue", request, TicketResponse.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTicketId()).startsWith("tkt_");
        assertThat(response.getBody().getStatus()).isEqualTo(TicketStatus.PENDING);
    }

    @Test
    @DisplayName("재고 소진 → 즉시 400 Bad Request 거부")
    void issueCoupon_outOfStock() {
        // given: 재고 0인 쿠폰 생성 (Redis에 재고 초기화 없음)
        createCoupon(0);
        CouponIssueRequest request = new CouponIssueRequest(COUPON_CODE, 1L, "127.0.0.1");

        // when
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v2/coupons/issue", request, String.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("발급 접수 후 Redis에서 PENDING 티켓 조회 가능")
    void issueCoupon_ticketStoredInRedis() {
        // given
        createCoupon(10);
        CouponIssueRequest request = new CouponIssueRequest(COUPON_CODE, 2L, "127.0.0.1");

        // when
        ResponseEntity<TicketResponse> issueResponse = restTemplate.postForEntity(
                "/api/v2/coupons/issue", request, TicketResponse.class);

        // then
        assertThat(issueResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String ticketId = issueResponse.getBody().getTicketId();

        // Redis에 PENDING 상태로 즉시 저장되어야 함
        ResponseEntity<TicketResponse> statusResponse = restTemplate.getForEntity(
                "/api/v2/coupons/status/" + ticketId, TicketResponse.class);

        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody().getStatus()).isEqualTo(TicketStatus.PENDING);
        assertThat(statusResponse.getBody().getTicketId()).isEqualTo(ticketId);
    }

    /**
     * Consumer 없이는 PENDING → COMPLETED 전이가 발생하지 않으므로 disabled 처리.
     * coupon-consumer 모듈이 동일 JVM에서 실행될 때만 의미 있는 테스트입니다.
     * 실 통합 환경(Docker Compose)에서는 consumer가 별도 프로세스로 기동되어야 합니다.
     */
    @Test
    @Disabled("coupon-consumer가 동일 JVM에 없어 PENDING → COMPLETED 전이 불가. Docker Compose 환경에서 수동 검증 필요.")
    @DisplayName("polling으로 COMPLETED 결과 조회 (Consumer 필요)")
    void getStatus_completedAfterConsumerProcessing() {
        createCoupon(10);
        CouponIssueRequest request = new CouponIssueRequest(COUPON_CODE, 3L, "127.0.0.1");
        ResponseEntity<TicketResponse> issueResponse = restTemplate.postForEntity(
                "/api/v2/coupons/issue", request, TicketResponse.class);

        assertThat(issueResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String ticketId = issueResponse.getBody().getTicketId();

        // 이 지점에서 Consumer가 없으면 영원히 PENDING 상태 유지
        ResponseEntity<TicketResponse> statusResponse = restTemplate.getForEntity(
                "/api/v2/coupons/status/" + ticketId, TicketResponse.class);

        assertThat(statusResponse.getBody().getStatus()).isEqualTo(TicketStatus.COMPLETED);
    }
}
