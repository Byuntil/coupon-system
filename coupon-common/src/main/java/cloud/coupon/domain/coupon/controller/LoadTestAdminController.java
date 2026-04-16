package cloud.coupon.domain.coupon.controller;

import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.entity.CouponType;
import cloud.coupon.domain.coupon.repository.CouponIssueRepository;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import cloud.coupon.domain.history.repository.CouponIssueHistoryRepository;
import cloud.coupon.infra.redis.service.RedisStockService;
import cloud.coupon.infra.redis.service.RedisStreamService;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cloud.coupon.domain.coupon.entity.IssueResult.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/load-test")
@RequiredArgsConstructor
@Profile("loadtest")
public class LoadTestAdminController {
    private static final String PHASE3_STREAM_KEY = "coupon:issue:stream";
    private static final String PHASE3_DLQ_KEY = "coupon:issue:dlq";
    private static final String PHASE3_GROUP_NAME = "coupon-issue-group";

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueHistoryRepository couponIssueHistoryRepository;
    private final RedisStockService redisStockService;
    private final RedisStreamService redisStreamService;

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

    @PostMapping("/analyze-ordering")
    public ResponseEntity<Map<String, Object>> analyzeOrdering(@RequestBody TeardownRequest request) {
        String code = request.couponCode();

        long totalFail    = couponIssueHistoryRepository.countByCodeAndResult(code, FAIL);
        long totalSuccess = couponIssueHistoryRepository.countByCodeAndResult(code, SUCCESS);

        // 순서 역전: 실패한 요청 중 서버 수신 시점이 성공한 요청보다 빠른 경우
        long violations = couponIssueHistoryRepository.countOrderingViolations(code);

        log.info("[LoadTest] 순서 역전 분석 | code: {} | success: {} | fail: {} | violations: {}",
                code, totalSuccess, totalFail, violations);

        return ResponseEntity.ok(Map.of(
                "couponCode", code,
                "successCount", totalSuccess,
                "failCount", totalFail,
                "orderingViolations", violations,
                "violationRate", totalFail > 0 ? String.format("%.2f%%", (double) violations / totalFail * 100) : "0%"
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

    @Transactional
    @PostMapping("/setup-phase3")
    public ResponseEntity<Map<String, Object>> setupPhase3(@RequestBody SetupRequest request) {
        log.info("[LoadTest-Phase3] 셋업 시작 | code: {} stock: {}", request.couponCode(), request.totalStock());

        String activeCouponCode = redisStockService.getPhase3AdminLockOwner();
        if (activeCouponCode != null || !redisStockService.tryAcquirePhase3AdminLock(request.couponCode())) {
            String lockOwner = activeCouponCode != null ? activeCouponCode : redisStockService.getPhase3AdminLockOwner();
            return phase3LockConflict("setup", request.couponCode(), lockOwner);
        }

        try {
            // 기존 DB 데이터 정리
            couponRepository.findByCode(request.couponCode()).ifPresent(coupon -> {
                couponIssueRepository.deleteByCouponId(coupon.getId());
                couponIssueHistoryRepository.deleteByCode(coupon.getCode());
                couponRepository.delete(coupon);
            });

            // Phase 3 Redis 키 전체 정리 (stock + inflight + issued + ticket)
            redisStockService.deleteAllPhase3Keys();
            clearPhase3StreamState();

            // 쿠폰 생성
            Coupon coupon = Coupon.builder()
                    .name(request.couponName() != null ? request.couponName() : "Phase3 부하테스트 쿠폰")
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
            recreatePhase3ConsumerGroup(request.couponCode());
        } catch (RuntimeException e) {
            if (!redisStockService.releasePhase3AdminLock(request.couponCode())) {
                log.warn("[LoadTest-Phase3] setup 실패 후 admin lock 해제 실패 | code: {}", request.couponCode());
            }
            throw e;
        }

        log.info("[LoadTest-Phase3] 셋업 완료 | code: {} stock: {}", request.couponCode(), request.totalStock());

        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "couponCode", request.couponCode(),
                "totalStock", request.totalStock(),
                "phase", "3"
        ));
    }

    @PostMapping("/verify-phase3")
    public ResponseEntity<Map<String, Object>> verifyPhase3(@RequestBody TeardownRequest request) {
        String code = request.couponCode();

        // DB 검증
        long dbIssuedCount = couponIssueRepository.countByCouponCode(code);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("couponCode", code);

        couponRepository.findByCode(code).ifPresentOrElse(coupon -> {
            result.put("totalStock", coupon.getTotalStock());
            result.put("dbRemainStock", coupon.getRemainStock());
            result.put("dbIssuedCount", dbIssuedCount);
            result.put("dbConsistent", (coupon.getTotalStock() - coupon.getRemainStock()) == dbIssuedCount);
        }, () -> {
            result.put("error", "coupon not found in DB");
        });

        // Redis 검증
        org.springframework.data.redis.core.RedisTemplate<String, String> rt = redisStockService.getRedisTemplate();
        String redisStock = rt.opsForValue().get("coupon:stock:" + code);
        Long inflightSize = rt.opsForSet().size("coupon:inflight:" + code);
        Long issuedSize = rt.opsForSet().size("coupon:issued:" + code);

        result.put("redisRemainStock", redisStock != null ? Integer.parseInt(redisStock) : "N/A");
        result.put("inflightCount", inflightSize != null ? inflightSize : 0);
        result.put("issuedSetCount", issuedSize != null ? issuedSize : 0);

        // Stream 검증
        try {
            Long streamLen = rt.opsForStream().size("coupon:issue:stream");
            result.put("streamLength", streamLen != null ? streamLen : 0);
        } catch (Exception e) {
            result.put("streamLength", "N/A");
        }

        // DLQ 검증
        try {
            Long dlqLen = rt.opsForStream().size("coupon:issue:dlq");
            result.put("dlqLength", dlqLen != null ? dlqLen : 0);
        } catch (Exception e) {
            result.put("dlqLength", 0);
        }

        // 정합성 요약
        boolean allProcessed = (inflightSize == null || inflightSize == 0);
        result.put("allProcessed", allProcessed);

        log.info("[LoadTest-Phase3] 검증 결과: {}", result);
        return ResponseEntity.ok(result);
    }

    @Transactional
    @PostMapping("/teardown-phase3")
    public ResponseEntity<Map<String, Object>> teardownPhase3(@RequestBody TeardownRequest request) {
        log.info("[LoadTest-Phase3] 정리 시작 | code: {}", request.couponCode());

        String activeCouponCode = redisStockService.getPhase3AdminLockOwner();
        if (activeCouponCode == null || !request.couponCode().equals(activeCouponCode)) {
            return phase3LockConflict("teardown", request.couponCode(), activeCouponCode);
        }

        couponRepository.findByCode(request.couponCode()).ifPresent(coupon -> {
            couponIssueRepository.deleteByCouponId(coupon.getId());
            couponIssueHistoryRepository.deleteByCode(coupon.getCode());
            couponRepository.delete(coupon);
        });

        redisStockService.deleteAllPhase3Keys();
        clearPhase3StreamState();
        releasePhase3AdminLockOrThrow(request.couponCode());

        log.info("[LoadTest-Phase3] 정리 완료 | code: {}", request.couponCode());
        return ResponseEntity.ok(Map.of("status", "OK"));
    }

    private ResponseEntity<Map<String, Object>> phase3LockConflict(String action, String requestedCouponCode,
                                                                   String activeCouponCode) {
        String message = activeCouponCode == null
                ? "활성화된 Phase 3 load test가 없습니다."
                : "다른 Phase 3 load test가 진행 중입니다.";

        return ResponseEntity.status(409).body(Map.of(
                "status", "CONFLICT",
                "phase", "3",
                "action", action,
                "requestedCouponCode", requestedCouponCode,
                "activeCouponCode", activeCouponCode != null ? activeCouponCode : "NONE",
                "message", message
        ));
    }

    private void clearPhase3StreamState() {
        try {
            redisStockService.getRedisTemplate().delete(PHASE3_STREAM_KEY);
            redisStockService.getRedisTemplate().delete(PHASE3_DLQ_KEY);
        } catch (Exception e) {
            throw new IllegalStateException("[LoadTest-Phase3] Stream/DLQ 정리 실패", e);
        }
    }

    private void recreatePhase3ConsumerGroup(String couponCode) {
        RecordId placeholderId;
        try {
            placeholderId = redisStockService.getRedisTemplate().opsForStream().add(
                    MapRecord.create(PHASE3_STREAM_KEY, Map.of(
                            "type", "phase3-setup-placeholder",
                            "couponCode", couponCode
                    ))
            );
            if (placeholderId == null) {
                throw new IllegalStateException("[LoadTest-Phase3] Consumer Group 생성을 위한 Stream 초기화에 실패했습니다.");
            }

            redisStreamService.createGroupIfNotExists(PHASE3_STREAM_KEY, PHASE3_GROUP_NAME);
            redisStockService.getRedisTemplate().opsForStream().delete(PHASE3_STREAM_KEY, placeholderId);
        } catch (Exception e) {
            throw new IllegalStateException("[LoadTest-Phase3] Consumer Group 준비 실패", e);
        }
    }

    private void releasePhase3AdminLockOrThrow(String couponCode) {
        if (!redisStockService.releasePhase3AdminLock(couponCode)) {
            throw new IllegalStateException("[LoadTest-Phase3] admin lock 해제 실패");
        }
    }
}
