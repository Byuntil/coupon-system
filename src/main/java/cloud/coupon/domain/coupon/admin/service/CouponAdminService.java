package cloud.coupon.domain.coupon.admin.service;

import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_ALREADY_DELETED_ERROR_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_ALREADY_EXISTS_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_ALREADY_USED_ERROR_MESSAGE;
import static cloud.coupon.domain.coupon.constant.ErrorMessage.COUPON_NOT_FOUND_MESSAGE;

import cloud.coupon.domain.coupon.admin.dto.request.CouponCreateRequest;
import cloud.coupon.domain.coupon.admin.dto.request.CouponUpdateRequest;
import cloud.coupon.domain.coupon.admin.dto.response.CouponResponse;
import cloud.coupon.domain.coupon.admin.dto.response.CouponStatusResponse;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponStatus;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.global.error.exception.coupon.CouponAlreadyDeletedException;
import cloud.coupon.global.error.exception.coupon.CouponAlreadyExistException;
import cloud.coupon.global.error.exception.coupon.CouponAlreadyUsedException;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponAdminService {

    private final CouponRepository couponRepository;

    // 쿠폰 생성 - 데이터 수정 필요
    @Transactional
    public CouponResponse createCoupon(CouponCreateRequest request) {
        validateAlreadyExistCoupon(request);

        Coupon savedCoupon = couponRepository.save(request.toEntity());
        return CouponResponse.from(savedCoupon);
    }


    // 쿠폰 수정 - 데이터 수정 필요
    @Transactional
    public CouponResponse updateCoupon(Long couponId, CouponUpdateRequest request) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CouponNotFoundException(COUPON_NOT_FOUND_MESSAGE));

        restrictUpdate(coupon);

        coupon.update(request);

        return CouponResponse.from(coupon);
    }

    private void restrictUpdate(Coupon coupon) {
        validateAlreadyUsedCoupon(coupon);
        validateDeletedCoupon(coupon);
    }

    @Transactional
    public void deleteCoupon(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CouponNotFoundException(COUPON_NOT_FOUND_MESSAGE));

        validateAlreadyUsedCoupon(coupon);
        validateDeletedCoupon(coupon);

        coupon.markAsDeleted();
        coupon.changeStatus(CouponStatus.EXPIRED);
    }

    // 발급 중단 - 데이터 수정 필요

    @Transactional
    public void stopCouponIssue(Long couponId) {
        // 중단 로직
    }
    // 조회 메서드들은 readOnly 적용

    public List<CouponStatusResponse> getCouponStatistics() {
        // 통계 조회 로직
        return null;
    }

    public CouponStatusResponse getCouponStatus(String code) {
        Coupon coupon = couponRepository.findByCodeAndIsDeletedFalse(code)
                .orElseThrow(() -> new CouponNotFoundException(COUPON_NOT_FOUND_MESSAGE));
        return CouponStatusResponse.from(coupon);
    }

    private void validateAlreadyExistCoupon(CouponCreateRequest request) {
        if (couponRepository.existsActiveCodeAndNotDeleted(request.code())) {
            throw new CouponAlreadyExistException(COUPON_ALREADY_EXISTS_MESSAGE);
        }
    }

    private void validateAlreadyUsedCoupon(Coupon coupon) {
        if (coupon.getUsedCount() > 0) {
            throw new CouponAlreadyUsedException(COUPON_ALREADY_USED_ERROR_MESSAGE);
        }
    }

    private void validateDeletedCoupon(Coupon coupon) {
        if (coupon.isDeleted()) {
            throw new CouponAlreadyDeletedException(COUPON_ALREADY_DELETED_ERROR_MESSAGE);
        }
    }
}
