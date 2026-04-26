#!/usr/bin/env bash
set -e

BASE_URL="${BASE_URL:-http://localhost:8080}"
GAP="${GAP:-30}"
INFLUX_URL="${INFLUX_URL:-http://localhost:8086/k6}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.phase3.yml}"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }

wait_gap() {
    local label=$1
    echo -e "${YELLOW}--- ${label} 완료. Grafana 구분을 위해 ${GAP}초 대기 ---${NC}"
    for i in $(seq "${GAP}" -1 1); do
        printf "\r  남은 시간: %2ds " "${i}"
        sleep 1
    done
    echo ""
}

log_info "=== Phase 3 부하 테스트 통합 실행 ==="
log_info "BASE_URL: ${BASE_URL}"
log_info "InfluxDB: ${INFLUX_URL}"
echo ""

if ! curl -s "${BASE_URL}/actuator/health" | grep -q '"UP"'; then
    echo "ERROR: API 서버가 응답하지 않습니다. docker-compose를 확인하세요."
    echo "  ./gradlew :coupon-api:bootJar :coupon-consumer:bootJar -x test"
    echo "  docker-compose -f ${COMPOSE_FILE} up -d"
    exit 1
fi

log_info "=== P3-1: 접수 처리량 측정 ==="
BASE_URL="${BASE_URL}" \
INFLUX_URL="${INFLUX_URL}" \
COMPOSE_FILE="${COMPOSE_FILE}" \
VUS=500 \
STOCK=1000000 \
COUPON_CODE="P3-1-CONSUMER-RUNNING" \
bash k6/run-phase3-admission-consumer-running.sh

wait_gap "P3-1"

log_info "=== P3-2: Drain 성능 측정 ==="
k6 run \
    --out "influxdb=${INFLUX_URL}" \
    -e BASE_URL="${BASE_URL}" \
    -e VUS=500 \
    -e STOCK=600 \
    -e COUPON_CODE="P3-2-VU500" \
    k6/phase3-drain.js

wait_gap "P3-2"

log_info "=== P3-3: 선착순/정합성 검증 ==="
k6 run \
    --out "influxdb=${INFLUX_URL}" \
    -e BASE_URL="${BASE_URL}" \
    -e VUS=1000 \
    -e STOCK=100 \
    -e COUPON_CODE="P3-3-FAIR" \
    k6/phase3-fairness.js

wait_gap "P3-3"

log_info "=== P3-4: 장애 복구 시나리오 ==="
bash k6/phase3-recovery.sh

echo ""
log_info "======================================================"
log_info "  Phase 3 전체 테스트 완료!"
log_info "  Grafana: http://localhost:3000 (Phase 3 대시보드)"
log_info "======================================================"
