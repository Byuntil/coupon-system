package cloud.coupon.domain.coupon.admin.service;

import cloud.coupon.domain.coupon.admin.dto.request.CouponCreateRequest;
import cloud.coupon.domain.coupon.admin.dto.request.CouponUpdateRequest;
import cloud.coupon.domain.coupon.admin.dto.response.CouponResponse;
import cloud.coupon.domain.coupon.admin.dto.response.CouponStatusResponse;
import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.global.error.exception.coupon.CouponAlreadyExistException;
import cloud.coupon.global.error.exception.coupon.CouponNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponAdminService {
    public static final String COUPON_NOT_FOUND_MESSAGE = "존재하지 않는 쿠폰입니다.";
    public static final String COUPON_ALREADY_EXISTS_MESSAGE = "이미 존재하는 쿠폰입니다.";

    private final CouponRepository couponRepository;

    // 쿠폰 생성 - 데이터 수정 필요
    @Transactional
    public CouponResponse createCoupon(CouponCreateRequest request) {
        validateAlreadyExistCoupon(request);

        Coupon savedCoupon = couponRepository.save(bulidCoupon(request));
        return CouponResponse.from(savedCoupon);
    }

    private void validateAlreadyExistCoupon(CouponCreateRequest request) {
        if (couponRepository.existsCouponByCode(request.code())) {
            throw new CouponAlreadyExistException(COUPON_ALREADY_EXISTS_MESSAGE);
        }
    }

    // 쿠폰 수정 - 데이터 수정 필요
    @Transactional
    public void updateCoupon(Long couponId, CouponUpdateRequest request) {
        // 수정 로직
    }
    // 발급 중단 - 데이터 수정 필요

    @Transactional
    public void stopCouponIssue(Long couponId) {
        // 중단 로직
    }

    public CouponStatusResponse getCouponStatus(String code) {
        Coupon coupon = couponRepository.findByCode(code)
                .orElseThrow(() -> new CouponNotFoundException(COUPON_NOT_FOUND_MESSAGE));
        return CouponStatusResponse.from(coupon);
    }

    // 조회 메서드들은 readOnly 적용

    public List<CouponStatusResponse> getCouponStatistics() {
        // 통계 조회 로직
        return null;
    }

    private Coupon bulidCoupon(CouponCreateRequest request) {
        return Coupon.builder()
                .name(request.name())
                .code(request.code())
                .totalStock(request.totalStock())
                .type(request.couponType())
                .discountValue(request.discountValue())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .expireTime(request.expireTime())
                .build();
    }
}
