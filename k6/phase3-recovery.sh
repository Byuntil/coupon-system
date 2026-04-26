#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
COUPON_CODE="${COUPON_CODE:-P3-RECOVERY-001}"
STOCK="${STOCK:-500}"
VUS="${VUS:-500}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.phase3.yml}"
CONSUMER_SERVICE="${CONSUMER_SERVICE:-coupon-consumer}"
CLAIM_WAIT="${CLAIM_WAIT:-75}"
RECOVERY_MAX_WAIT="${RECOVERY_MAX_WAIT:-120}"
STREAM_KEY="${STREAM_KEY:-coupon:issue:stream}"
GROUP_NAME="${GROUP_NAME:-coupon-issue-group}"
STUCK_CONSUMER="${STUCK_CONSUMER:-stuck-consumer}"
EXPECTED_ACCEPTED="${EXPECTED_ACCEPTED:-}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

api_post() {
    local path=$1
    local body=$2
    curl -s -X POST "${BASE_URL}${path}" \
        -H "Content-Type: application/json" \
        -d "${body}"
}

compose() {
    docker-compose -f "${COMPOSE_FILE}" "$@"
}

redis_cli() {
    compose exec -T redis redis-cli "$@"
}

json_get() {
    local json=$1
    local key=$2
    echo "${json}" | python3 -c "import sys,json; print(json.load(sys.stdin).get('${key}', -1))" 2>/dev/null || echo "-1"
}

cleanup() {
    local exit_code=${1:-$?}
    set +e

    log_info "정리 중..."
    compose stop "${CONSUMER_SERVICE}" > /dev/null
    api_post "/api/v1/admin/load-test/teardown-phase3" "{\"couponCode\":\"${COUPON_CODE}\"}" > /dev/null
    compose start "${CONSUMER_SERVICE}" > /dev/null

    return "${exit_code}"
}

wait_for_api() {
    local max_tries=30
    for i in $(seq 1 "${max_tries}"); do
        if curl -s "${BASE_URL}/actuator/health" | grep -q '"UP"'; then
            return 0
        fi
        sleep 2
    done
    log_error "API 서버가 응답하지 않습니다."
    return 1
}

log_info "=== P3-4: Phase 3 장애 복구 시나리오 시작 ==="
log_info "Stock: ${STOCK} | VUs: ${VUS} | Coupon: ${COUPON_CODE}"

if [ -z "${EXPECTED_ACCEPTED}" ]; then
    if [ "${VUS}" -lt "${STOCK}" ]; then
        EXPECTED_ACCEPTED="${VUS}"
    else
        EXPECTED_ACCEPTED="${STOCK}"
    fi
fi

log_info "Expected accepted: ${EXPECTED_ACCEPTED}"

log_info "API 서버 대기 중..."
wait_for_api

trap 'cleanup $?' EXIT

log_info "Consumer 중지..."
compose stop "${CONSUMER_SERVICE}" > /dev/null

log_info "Phase 3 셋업..."
SETUP_RES=$(api_post "/api/v1/admin/load-test/setup-phase3" \
    "{\"couponCode\":\"${COUPON_CODE}\",\"couponName\":\"P3-4 장애 복구 테스트\",\"totalStock\":${STOCK}}")
echo "Setup 결과: ${SETUP_RES}"

log_info "Backlog 생성 중 (k6 per-vu-iterations burst)..."
k6 run --quiet \
    -e BASE_URL="${BASE_URL}" \
    -e VUS="${VUS}" \
    -e STOCK="${STOCK}" \
    -e COUPON_CODE="${COUPON_CODE}" \
    - <<'K6_SCRIPT'
import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL;
const COUPON_CODE = __ENV.COUPON_CODE;
const VUS = parseInt(__ENV.VUS || '500', 10);

export const options = {
    scenarios: {
        burst: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '30s',
        },
    },
};

export default function () {
    const userId = __VU;
    http.post(
        `${BASE_URL}/api/v2/coupons/issue`,
        JSON.stringify({ code: COUPON_CODE, userId, requestIp: '127.0.0.1' }),
        { headers: { 'Content-Type': 'application/json' }, timeout: '10s' }
    );
}
K6_SCRIPT

log_info "Backlog 생성 완료"

log_info "stuck consumer로 PEL 생성 중..."
redis_cli XREADGROUP GROUP "${GROUP_NAME}" "${STUCK_CONSUMER}" COUNT "${EXPECTED_ACCEPTED}" STREAMS "${STREAM_KEY}" ">" > /dev/null

PENDING_COUNT=$(redis_cli XPENDING "${STREAM_KEY}" "${GROUP_NAME}" | sed -n '1p')
log_info "XPENDING count: ${PENDING_COUNT}"

if [ "${PENDING_COUNT}" -ne "${EXPECTED_ACCEPTED}" ]; then
    log_error "PEL 생성 실패: pending=${PENDING_COUNT}, expected=${EXPECTED_ACCEPTED}"
    exit 1
fi

log_info "중간 상태 확인..."
MID_RES=$(api_post "/api/v1/admin/load-test/verify-phase3" \
    "{\"couponCode\":\"${COUPON_CODE}\"}")
echo "중간 상태: ${MID_RES}"

log_info "claim-idle-time 대기 (${CLAIM_WAIT}초)..."
for i in $(seq "${CLAIM_WAIT}" -1 1); do
    printf "\r  남은 시간: %3ds " "${i}"
    sleep 1
done
echo ""

log_info "Consumer 재시작..."
compose start "${CONSUMER_SERVICE}" > /dev/null

log_info "Consumer 복구 대기 (최대 ${RECOVERY_MAX_WAIT}초)..."
FINAL_RES=""
for i in $(seq 1 "${RECOVERY_MAX_WAIT}"); do
    FINAL_RES=$(api_post "/api/v1/admin/load-test/verify-phase3" \
        "{\"couponCode\":\"${COUPON_CODE}\"}")
    DB_ISSUED=$(json_get "${FINAL_RES}" "dbIssuedCount")
    INFLIGHT=$(json_get "${FINAL_RES}" "inflightCount")
    DLQ_LEN=$(json_get "${FINAL_RES}" "dlqLength")
    TOTAL_PROCESSED=$((DB_ISSUED + DLQ_LEN))

    if [ "${INFLIGHT}" -eq "0" ] && [ "${TOTAL_PROCESSED}" -eq "${EXPECTED_ACCEPTED}" ]; then
        printf "\r  복구 완료: %3ds 경과\n" "${i}"
        break
    fi

    printf "\r  경과 시간: %3ds | 발급+DLQ: %s/%s | inflight: %s " \
        "${i}" "${TOTAL_PROCESSED}" "${EXPECTED_ACCEPTED}" "${INFLIGHT}"
    sleep 1
done
echo ""

log_info "최종 검증..."
if [ -z "${FINAL_RES}" ]; then
    FINAL_RES=$(api_post "/api/v1/admin/load-test/verify-phase3" \
        "{\"couponCode\":\"${COUPON_CODE}\"}")
fi

echo ""
log_info "=== P3-4 최종 검증 결과 ==="
echo "${FINAL_RES}" | python3 -m json.tool 2>/dev/null || echo "${FINAL_RES}"

DB_ISSUED=$(json_get "${FINAL_RES}" "dbIssuedCount")
INFLIGHT=$(json_get "${FINAL_RES}" "inflightCount")
DLQ_LEN=$(json_get "${FINAL_RES}" "dlqLength")
DB_CONSISTENT=$(json_get "${FINAL_RES}" "dbConsistent")
REDIS_REMAIN_STOCK=$(json_get "${FINAL_RES}" "redisRemainStock")

echo ""
log_info "=== 판정 ==="

PASS=true
TOTAL_PROCESSED=$((DB_ISSUED + DLQ_LEN))
EXPECTED_REDIS_STOCK=$((STOCK - EXPECTED_ACCEPTED))

if [ "${TOTAL_PROCESSED}" -eq "${EXPECTED_ACCEPTED}" ]; then
    log_info "유실 메시지: 0건 (발급=${DB_ISSUED} + DLQ=${DLQ_LEN} = ${TOTAL_PROCESSED}/${EXPECTED_ACCEPTED})"
else
    log_error "유실 가능: 발급=${DB_ISSUED} + DLQ=${DLQ_LEN} = ${TOTAL_PROCESSED} (expected: ${EXPECTED_ACCEPTED})"
    PASS=false
fi

if [ "${INFLIGHT}" -eq "0" ]; then
    log_info "Inflight 잔류: 0건"
else
    log_error "Inflight 잔류: ${INFLIGHT}건"
    PASS=false
fi

if [ "${DB_CONSISTENT}" = "True" ]; then
    log_info "DB 정합성: PASS"
else
    log_error "DB 정합성: FAIL"
    PASS=false
fi

if [ "${REDIS_REMAIN_STOCK}" -eq "${EXPECTED_REDIS_STOCK}" ]; then
    log_info "Redis remaining stock: ${REDIS_REMAIN_STOCK}"
else
    log_error "Redis remaining stock 불일치: ${REDIS_REMAIN_STOCK} (expected: ${EXPECTED_REDIS_STOCK})"
    PASS=false
fi

if [ "${DLQ_LEN}" -gt "0" ]; then
    log_warn "DLQ에 ${DLQ_LEN}건 이동됨 (장애 복구 중 max-retry 초과)"
else
    log_info "DLQ: 0건"
fi

echo ""
if [ "${PASS}" = true ]; then
    log_info "=== P3-4 결과: PASS ==="
    RESULT_EXIT=0
else
    log_error "=== P3-4 결과: FAIL ==="
    RESULT_EXIT=1
fi

trap - EXIT
set +e
cleanup "${RESULT_EXIT}"
set -e
log_info "=== P3-4 테스트 종료 ==="
exit "${RESULT_EXIT}"
