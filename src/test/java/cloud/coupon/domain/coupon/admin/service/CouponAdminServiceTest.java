package cloud.coupon.domain.coupon.admin.service;

import static cloud.coupon.domain.coupon.admin.service.CouponAdminService.COUPON_ALREADY_EXISTS_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cloud.coupon.domain.coupon.admin.dto.request.CouponCreateRequest;
import cloud.coupon.domain.coupon.admin.dto.response.CouponResponse;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.global.error.exception.coupon.CouponAlreadyExistException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CouponAdminServiceTest {

    @Autowired
    private CouponAdminService couponAdminService;

    @Autowired
    private CouponRepository couponRepository;

    private CouponCreateRequest request;

    @BeforeEach
    void setUp() {
        couponRepository.deleteAll();
        Coupon coupon = Coupon.builder()
                .name("테스트 쿠폰")
                .code("TEST-0001")
                .totalStock(10)
                .startTime(LocalDateTime.of(2023, 1, 1, 0, 0))
                .endTime(LocalDateTime.of(2024, 1, 31, 23, 59))
                .expireTime(LocalDateTime.of(3000, 1, 31, 23, 59))
                .build();
        request = CouponCreateRequest.from(coupon);
    }

    @Test
    @DisplayName("createCoupon 메서드는 쿠폰을 만든다")
    void createCouponTest() {
        // when
        CouponResponse createdCoupon = couponAdminService.createCoupon(request);
        // then
        assertThat(createdCoupon.code()).isEqualTo(request.code());
    }

    @Test
    @DisplayName("createCoupon 메서드는 이미 존재하는 쿠폰이 있을시 예외를 던진다")
    void createCouponAlreadyExistErrorTest() {
        // given
        couponAdminService.createCoupon(request);
        // when
        assertThatThrownBy(() -> couponAdminService.createCoupon(request)).
                isInstanceOf(CouponAlreadyExistException.class)
                .hasMessage(COUPON_ALREADY_EXISTS_MESSAGE);
    }
}