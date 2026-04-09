package cloud.coupon.infra.redis.service;

import cloud.coupon.domain.coupon.entity.Coupon;
import cloud.coupon.domain.coupon.repository.CouponRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coupon.scheduler.stock-sync-enabled", havingValue = "true", matchIfMissing = true)
public class StockSyncScheduler {

    private final CouponRepository couponRepository;
    private final RedisStockService redisStockService;

    /**
     * 주기적으로 active 쿠폰의 Redis 재고를 DB 기준으로 보정합니다.
     * repair/reconciliation 용도이며, 정상 경로 정합성 보장 메커니즘이 아닙니다.
     *
     * 초기 버전: active coupon 전체를 순회.
     * 쿠폰 수가 커지면 최근 발급된 subset 또는 불일치 감지 대상만 처리하도록 범위 축소 예정.
     */
    @Scheduled(fixedDelayString = "${coupon.stock-sync-delay-ms:60000}")
    public void syncStocks() {
        log.debug("재고 동기화 스케줄러 실행");
        List<Coupon> activeCoupons = couponRepository.findAllActiveCoupons();
        for (Coupon coupon : activeCoupons) {
            try {
                redisStockService.syncStockWithDB(coupon.getCode(), coupon.getRemainStock());
            } catch (Exception e) {
                log.error("[{}] 재고 동기화 실패: {}", coupon.getCode(), e.getMessage());
            }
        }
    }
}
