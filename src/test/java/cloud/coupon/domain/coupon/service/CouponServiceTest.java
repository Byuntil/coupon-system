package cloud.coupon.domain.coupon.service;

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
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import cloud.coupon.global.error.exception.couponissue.CouponIssueNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CouponServiceTest {
    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    @Autowired
    private CouponIssueHistoryRepository couponIssueHistoryRepository;

    private String code;
    private final Long userId = 1L;
    private final String requestIp = "127.0.0.1";

    @BeforeEach
    void setUp() {
        couponRepository.deleteAll();
        couponIssueRepository.deleteAll();
        couponIssueHistoryRepository.deleteAll();

        // 테스트용 쿠폰 생성
        Coupon coupon = Coupon.builder()
                .name("테스트 쿠폰")
                .code("TEST-0001")
                .totalStock(10)
                .startTime(LocalDateTime.of(2023, 1, 1, 0, 0))
                .endTime(LocalDateTime.of(2024, 1, 31, 23, 59))
                .expireTime(LocalDateTime.of(3000, 1, 31, 23, 59))
                .build();

        code = couponRepository.save(coupon).getCode();
    }

    @Test
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
                .findByCouponCodeAndUserId(code, userId)
                .orElseThrow();
        assertThat(history.getResult()).isEqualTo(IssueResult.SUCCESS);
    }

    @Test
    @DisplayName("이미 발급받은 쿠폰을 재발급 시도시 예외 발생")
    void issueCoupon_duplicateIssue() {
        // given
        couponService.issueCoupon(new CouponIssueRequest(code, userId, requestIp));

        // when & then
        CouponIssueResult couponIssueResult = couponService.issueCoupon(
                new CouponIssueRequest(code, userId, requestIp));

        assertThat(couponIssueResult.isSuccess()).isFalse();
        // 히스토리 확인
        List<CouponIssueHistory> histories = couponIssueHistoryRepository
                .findByUserIdOrderByRequestTimeDesc(userId);

        assertThat(histories).hasSize(2);
        assertThat(histories.getFirst().getResult()).isEqualTo(IssueResult.FAIL);
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
                .hasMessage("존재하지 않는 쿠폰입니다.");
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
                .findByCouponCodeAndUserId(code, userId + 10)
                .orElseThrow();
        assertThat(history.getResult()).isEqualTo(IssueResult.FAIL);
    }

    @Test
    @DisplayName("쿠폰 사용 성공")
    void useCoupon_success() {
        // given
        CouponIssueResult couponIssueResult = couponService.issueCoupon(
                new CouponIssueRequest(code, userId, requestIp));// 쿠폰 발급

        // when
        couponService.useCoupon(couponIssueResult.getCouponCode(), userId);

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
                couponService.useCoupon("INVALID_CODE", userId))
                .isInstanceOf(CouponIssueNotFoundException.class)
                .hasMessageContaining("발급된 쿠폰을 찾을 수 없습니다.");
    }
}