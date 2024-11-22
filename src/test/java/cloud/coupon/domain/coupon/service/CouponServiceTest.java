package cloud.coupon.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cloud.coupon.domain.coupon.dto.response.CouponIssueResult;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponIssue;
import cloud.coupon.domain.coupon.entity.IssueResult;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.domain.history.entity.CouponIssueHistory;
import cloud.coupon.domain.history.repository.CouponIssueHistoryRepository;
import cloud.coupon.global.error.exception.CouponNotFoundException;
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

    private Long savedCouponId;
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
                .totalStock(10)
                .remainStock(10)
                .build();

        savedCouponId = couponRepository.save(coupon).getId();
    }

    @Test
    @DisplayName("쿠폰 발급 성공")
    void issueCoupon_success() {
        // when
        CouponIssueResult result = couponService.issueCoupon(savedCouponId, userId, requestIp);

        // then
        assertThat(result.isSuccess()).isTrue();

        // DB 확인
        CouponIssue savedCouponIssue = couponIssueRepository.findById(result.getCouponIssueId())
                .orElseThrow();
        assertThat(savedCouponIssue.getUserId()).isEqualTo(userId);

        Coupon updatedCoupon = couponRepository.findById(savedCouponId)
                .orElseThrow();
        assertThat(updatedCoupon.getRemainStock()).isEqualTo(9);

        // 히스토리 확인
        CouponIssueHistory history = couponIssueHistoryRepository
                .findByCouponIdAndUserId(savedCouponId, userId)
                .orElseThrow();
        assertThat(history.getResult()).isEqualTo(IssueResult.SUCCESS);
    }

    @Test
    @DisplayName("이미 발급받은 쿠폰을 재발급 시도시 예외 발생")
    void issueCoupon_duplicateIssue() {
        // given
        couponService.issueCoupon(savedCouponId, userId, requestIp);

        // when & then
        CouponIssueResult couponIssueResult = couponService.issueCoupon(savedCouponId, userId, requestIp);

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
        Long nonExistentCouponId = 999L;

        // when & then
        assertThatThrownBy(() ->
                couponService.issueCoupon(nonExistentCouponId, userId, requestIp))
                .isInstanceOf(CouponNotFoundException.class)
                .hasMessage("존재하지 않는 쿠폰입니다.");
    }

    @Test
    @DisplayName("재고가 없는 쿠폰 발급 시도시 실패")
    void issueCoupon_outOfStock() {
        // given
        // 재고 소진
        for (int i = 0; i < 10; i++) {
            couponService.issueCoupon(savedCouponId, userId + i, requestIp);
        }

        // when
        CouponIssueResult result = couponService.issueCoupon(savedCouponId, userId + 10, requestIp);

        // then
        assertThat(result.isSuccess()).isFalse();

        // 히스토리 확인
        CouponIssueHistory history = couponIssueHistoryRepository
                .findByCouponIdAndUserId(savedCouponId, userId + 10)
                .orElseThrow();
        assertThat(history.getResult()).isEqualTo(IssueResult.FAIL);
    }
}