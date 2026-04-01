package cloud.coupon.domain.coupon.controller;

import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponType;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.domain.history.repository.CouponIssueHistoryRepository;
import cloud.coupon.infra.redis.service.RedisStockService;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/load-test")
@RequiredArgsConstructor
@Profile("loadtest")
public class LoadTestAdminController {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueHistoryRepository couponIssueHistoryRepository;
    private final RedisStockService redisStockService;

    public record SetupRequest(
            String couponCode,
            String couponName,
            int totalStock
    ) {}

    public record TeardownRequest(String couponCode) {}

    public record ResetRequest(String couponCode, int totalStock) {}

    @Transactional
    @PostMapping("/setup")
    public ResponseEntity<Map<String, Object>> setup(@RequestBody SetupRequest request) {
        log.info("[LoadTest] 셋업 시작 | code: {} stock: {}", request.couponCode(), request.totalStock());

        // 기존 데이터 정리
        couponRepository.findByCode(request.couponCode()).ifPresent(coupon -> {
            couponIssueRepository.deleteByCouponId(coupon.getId());
            couponIssueHistoryRepository.deleteByCode(coupon.getCode());
            couponRepository.delete(coupon);
        });
        redisStockService.deleteAllKeys();

        // 쿠폰 생성 (발급 기간: 현재 ~ 1시간 후, 만료: 1년 후)
        Coupon coupon = Coupon.builder()
                .name(request.couponName() != null ? request.couponName() : "부하테스트 쿠폰")
                .code(request.couponCode())
                .totalStock(request.totalStock())
                .type(CouponType.FIXED_AMOUNT)
                .discountValue(1000)
                .startTime(LocalDateTime.now().minusMinutes(1))
                .endTime(LocalDateTime.now().plusHours(1))
                .expireTime(LocalDateTime.now().plusYears(1))
                .build();

        couponRepository.save(coupon);
        redisStockService.initializeStock(request.couponCode(), request.totalStock());

        log.info("[LoadTest] 셋업 완료 | code: {} stock: {}", request.couponCode(), request.totalStock());

        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "couponCode", request.couponCode(),
                "totalStock", request.totalStock()
        ));
    }

    @Transactional
    @PostMapping("/teardown")
    public ResponseEntity<Map<String, Object>> teardown(@RequestBody TeardownRequest request) {
        log.info("[LoadTest] 정리 시작 | code: {}", request.couponCode());

        couponRepository.findByCode(request.couponCode()).ifPresent(coupon -> {
            long issuedCount = couponIssueRepository.countByCouponCode(request.couponCode());
            int remainStock = coupon.getRemainStock();

            log.info("[LoadTest] 정합성 검증 | code: {} | totalStock: {} | remainStock: {} | issuedCount: {}",
                    request.couponCode(), coupon.getTotalStock(), remainStock, issuedCount);

            couponIssueRepository.deleteByCouponId(coupon.getId());
            couponIssueHistoryRepository.deleteByCode(coupon.getCode());
            couponRepository.delete(coupon);
        });

        redisStockService.deleteAllKeys();

        log.info("[LoadTest] 정리 완료 | code: {}", request.couponCode());
        return ResponseEntity.ok(Map.of("status", "OK"));
    }

    @Transactional
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestBody ResetRequest request) {
        log.info("[LoadTest] 리셋 시작 | code: {} stock: {}", request.couponCode(), request.totalStock());

        // 발급 데이터만 정리, 쿠폰 마스터는 유지
        couponRepository.findByCode(request.couponCode()).ifPresent(coupon -> {
            couponIssueRepository.deleteByCouponId(coupon.getId());
            couponIssueHistoryRepository.deleteByCode(coupon.getCode());
            // 쿠폰 삭제 후 재생성 (remainStock 초기화를 위해)
            couponRepository.delete(coupon);

            Coupon newCoupon = Coupon.builder()
                    .name(coupon.getName())
                    .code(coupon.getCode())
                    .totalStock(request.totalStock())
                    .type(coupon.getType())
                    .discountValue(coupon.getDiscountValue())
                    .startTime(LocalDateTime.now().minusMinutes(1))
                    .endTime(LocalDateTime.now().plusHours(1))
                    .expireTime(LocalDateTime.now().plusYears(1))
                    .build();
            couponRepository.save(newCoupon);
        });

        redisStockService.deleteAllKeys();
        redisStockService.initializeStock(request.couponCode(), request.totalStock());

        log.info("[LoadTest] 리셋 완료 | code: {} stock: {}", request.couponCode(), request.totalStock());
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "couponCode", request.couponCode(),
                "totalStock", request.totalStock()
        ));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody TeardownRequest request) {
        return couponRepository.findByCode(request.couponCode())
                .map(coupon -> {
                    long issuedCount = couponIssueRepository.countByCouponCode(request.couponCode());
                    int remainStock = coupon.getRemainStock();
                    int totalStock = coupon.getTotalStock();
                    boolean consistent = (totalStock - remainStock) == issuedCount;

                    return ResponseEntity.ok(Map.<String, Object>of(
                            "couponCode", request.couponCode(),
                            "totalStock", totalStock,
                            "remainStock", remainStock,
                            "issuedCount", issuedCount,
                            "consistent", consistent
                    ));
                })
                .orElse(ResponseEntity.ok(Map.of("error", "coupon not found")));
    }
}
