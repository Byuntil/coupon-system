#!/usr/bin/env bash
set -e

BASE_URL="${BASE_URL:-http://localhost:8080}"
STOCK="${STOCK:-1000}"
GAP="${GAP:-30}"  # 테스트 간 대기 시간 (초)

run_test() {
  local vus=$1
  local code=$2
  echo ""
  echo "======================================================"
  echo "  Phase1 Redis 부하테스트: VU=${vus}, STOCK=${STOCK}"
  echo "======================================================"
  k6 run \
    --out influxdb=http://localhost:8086/k6 \
    -e BASE_URL="${BASE_URL}" \
    -e VUS="${vus}" \
    -e STOCK="${STOCK}" \
    -e COUPON_CODE="${code}" \
    k6/phase1-redis.js
}

wait_gap() {
  local vus=$1
  echo ""
  echo "------------------------------------------------------"
  echo "  ⏸  다음 테스트까지 ${GAP}초 대기 중 (Grafana 구분 구간)"
  echo "------------------------------------------------------"
  for i in $(seq "${GAP}" -1 1); do
    printf "\r  남은 시간: %2ds " "${i}"
    sleep 1
  done
  echo ""
}

# VU=200
run_test 200 "PHASE1-VU200"
wait_gap 200

# VU=500
run_test 500 "PHASE1-VU500"
wait_gap 500

# VU=1000
run_test 1000 "PHASE1-VU1000"

echo ""
echo "======================================================"
echo "  모든 테스트 완료!"
echo "  Grafana에서 Last 10m 기준으로 확인하세요."
echo "======================================================"
