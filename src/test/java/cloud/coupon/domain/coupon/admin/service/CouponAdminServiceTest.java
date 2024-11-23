package cloud.coupon.domain.coupon.admin.service;

import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_ALREADY_DELETED_ERROR_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_ALREADY_EXISTS_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_ALREADY_USED_ERROR_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_NOT_FOUND_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cloud.coupon.domain.coupon.admin.dto.request.CouponCreateRequest;
import cloud.coupon.domain.coupon.admin.dto.request.CouponUpdateRequest;
import cloud.coupon.domain.coupon.admin.dto.response.CouponResponse;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponType;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.global.error.exception.coupon.CouponAlreadyDeletedException;
import cloud.coupon.global.error.exception.coupon.CouponAlreadyExistException;
import cloud.coupon.global.error.exception.coupon.CouponAlreadyUsedException;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
                .type(CouponType.FIXED_AMOUNT)
                .discountValue(1000)
                .startTime(LocalDateTime.of(2023, 1, 1, 0, 0))
                .endTime(LocalDateTime.of(2024, 1, 31, 23, 59))
                .expireTime(LocalDateTime.of(3000, 1, 31, 23, 59))
                .build();
        request = CouponCreateRequest.from(coupon);
    }

    @Nested
    @DisplayName("createCoupon 메서드는")
    class CreateCouponTest {
        @Test
        @DisplayName("쿠폰을 만든다")
        void createCouponTest() {
            // when
            CouponResponse createdCoupon = couponAdminService.createCoupon(request);
            // then
            assertThat(createdCoupon.code()).isEqualTo(request.code());
        }

        @Test
        @DisplayName("이미 존재하는 쿠폰이 있을시 예외를 던진다")
        void createCouponAlreadyExistErrorTest() {
            // given
            couponAdminService.createCoupon(request);
            // when
            assertThatThrownBy(() -> couponAdminService.createCoupon(request)).
                    isInstanceOf(CouponAlreadyExistException.class)
                    .hasMessage(COUPON_ALREADY_EXISTS_MESSAGE);
        }
    }

    @Nested
    @DisplayName("updateCoupon 메서드는")
    class UpdateCouponTest {
        @Test
        @DisplayName("쿠폰의 정보를 수정한다.")
        void updateCouponTest() {
            // given
            LocalDateTime now = LocalDateTime.now();
            CouponResponse createdCoupon = couponAdminService.createCoupon(request);
            Coupon newCoupon = Coupon.builder()
                    .name("변경된 쿠폰")
                    .code("TEST-0001")
                    .totalStock(100)
                    .type(CouponType.FIXED_AMOUNT)
                    .discountValue(1000)
                    .startTime(now)
                    .endTime(now.plusDays(7))
                    .expireTime(now.plusDays(30))
                    .build();
            CouponUpdateRequest updateRequest = CouponUpdateRequest.from(newCoupon);
            // when
            CouponResponse couponResponse = couponAdminService.updateCoupon(createdCoupon.id(), updateRequest);
            // then
            Coupon foundCoupon = couponRepository.findById(createdCoupon.id()).orElseThrow();
            assertThat(foundCoupon.getId()).isEqualTo(createdCoupon.id());
            assertThat(foundCoupon.getCode()).isEqualTo(couponResponse.code());
            assertThat(foundCoupon.getName()).isEqualTo(couponResponse.name());
        }

        @Test
        @DisplayName("쿠폰이 없다면 예외를 던진다.")
        void updateCouponNotFoundErrorTest() {
            // given
            Long nonExistentId = 999L;
            CouponUpdateRequest updateRequest = CouponUpdateRequest.from(
                    Coupon.builder()
                            .name(request.name())
                            .code(request.code())
                            .totalStock(request.totalStock())
                            .type(request.couponType())
                            .discountValue(request.discountValue())
                            .startTime(request.startTime())
                            .endTime(request.endTime())
                            .expireTime(request.expireTime())
                            .build()
            );

            // when & then
            assertThatThrownBy(() -> couponAdminService.updateCoupon(nonExistentId, updateRequest))
                    .isInstanceOf(CouponNotFoundException.class)
                    .hasMessage(COUPON_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("이미 사용된 쿠폰을 수정하려고 하면 예외를 던진다.")
        void updateCouponAlreadyUsedTest() {
            // given
            CouponResponse createdCoupon = couponAdminService.createCoupon(request);
            Coupon coupon = couponRepository.findById(createdCoupon.id()).orElseThrow();
            coupon.increaseUsedCount();
            couponRepository.save(coupon);

            CouponUpdateRequest updateRequest = CouponUpdateRequest.from(
                    Coupon.builder()
                            .name(request.name())
                            .code(request.code())
                            .totalStock(request.totalStock())
                            .type(request.couponType())
                            .discountValue(request.discountValue())
                            .startTime(request.startTime())
                            .endTime(request.endTime())
                            .expireTime(request.expireTime())
                            .build()
            );

            // when & then
            assertThatThrownBy(() -> couponAdminService.updateCoupon(createdCoupon.id(), updateRequest))
                    .isInstanceOf(CouponAlreadyUsedException.class)
                    .hasMessage(COUPON_ALREADY_USED_ERROR_MESSAGE);
        }

        @Test
        @DisplayName("삭제된 쿠폰을 수정하려고 하면 예외를 던진다.")
        void updateCouponAlreadyDeletedTest() {
            // given
            CouponResponse createdCoupon = couponAdminService.createCoupon(request);
            Coupon coupon = couponRepository.findById(createdCoupon.id()).orElseThrow();
            coupon.markAsDeleted();
            couponRepository.save(coupon);

            CouponUpdateRequest updateRequest = CouponUpdateRequest.from(
                    Coupon.builder()
                            .name(request.name())
                            .code(request.code())
                            .totalStock(request.totalStock())
                            .type(request.couponType())
                            .discountValue(request.discountValue())
                            .startTime(request.startTime())
                            .endTime(request.endTime())
                            .expireTime(request.expireTime())
                            .build()
            );

            // when & then
            assertThatThrownBy(() -> couponAdminService.updateCoupon(createdCoupon.id(), updateRequest))
                    .isInstanceOf(CouponAlreadyDeletedException.class)
                    .hasMessage(COUPON_ALREADY_DELETED_ERROR_MESSAGE);
        }
    }

    @Nested
    @DisplayName("deleteCoupon 메서드는")
    class DeleteCouponTest {

        @Test
        @DisplayName("쿠폰을 삭제한다")
        void deleteCouponTest() {
            // given
            CouponResponse createdCoupon = couponAdminService.createCoupon(request);

            // when
            couponAdminService.deleteCoupon(createdCoupon.id());

            // then
            Coupon deletedCoupon = couponRepository.findById(createdCoupon.id()).orElseThrow();
            assertThat(deletedCoupon.isDeleted()).isTrue();
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰을 삭제하려고 하면 예외를 던진다")
        void deleteCouponNotFoundTest() {
            // given
            Long nonExistentId = 999L;

            // when & then
            assertThatThrownBy(() -> couponAdminService.deleteCoupon(nonExistentId))
                    .isInstanceOf(CouponNotFoundException.class)
                    .hasMessage(COUPON_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("이미 삭제된 쿠폰을 삭제하려고 하면 예외를 던진다")
        void deleteAlreadyDeletedCouponTest() {
            // given
            CouponResponse createdCoupon = couponAdminService.createCoupon(request);
            couponAdminService.deleteCoupon(createdCoupon.id());

            // when & then
            assertThatThrownBy(() -> couponAdminService.deleteCoupon(createdCoupon.id()))
                    .isInstanceOf(CouponAlreadyDeletedException.class)
                    .hasMessage(COUPON_ALREADY_DELETED_ERROR_MESSAGE);
        }

        @Test
        @DisplayName("이미 사용된 쿠폰을 삭제하려고 하면 예외를 던진다")
        void deleteUsedCouponTest() {
            // given
            CouponResponse createdCoupon = couponAdminService.createCoupon(request);
            Coupon coupon = couponRepository.findById(createdCoupon.id()).orElseThrow();
            coupon.increaseUsedCount();
            couponRepository.save(coupon);

            // when & then
            assertThatThrownBy(() -> couponAdminService.deleteCoupon(createdCoupon.id()))
                    .isInstanceOf(CouponAlreadyUsedException.class)
                    .hasMessage(COUPON_ALREADY_USED_ERROR_MESSAGE);
        }
    }
}