#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.phase3.yml}"
CONSUMER_SERVICE="${CONSUMER_SERVICE:-coupon-consumer}"
INFLUX_URL="${INFLUX_URL:-http://localhost:8086/k6}"
SUMMARY_EXPORT="${SUMMARY_EXPORT:-.omx/logs/load-test/p3-1-admission-consumer-running-summary.json}"
LOG_FILE="${LOG_FILE:-.omx/logs/load-test/p3-1-admission-consumer-running.log}"

VUS="${VUS:-500}"
STOCK="${STOCK:-1000000}"
COUPON_CODE="${COUPON_CODE:-P3-1-CONSUMER-RUNNING}"
DURATION="${DURATION:-30s}"
RAMP_UP="${RAMP_UP:-10s}"

DRY_RUN="${DRY_RUN:-false}"

log() {
    printf '[phase3-admission-consumer-running] %s\n' "$*" | tee -a "${LOG_FILE}"
}

run() {
    if [ "${DRY_RUN}" = "true" ]; then
        printf '+ %q' "$1"
        shift
        printf ' %q' "$@"
        printf '\n'
        return 0
    fi
    "$@"
}

api_post() {
    local path=$1
    local body=$2
    curl -fsS -X POST "${BASE_URL}${path}" \
        -H "Content-Type: application/json" \
        -d "${body}"
}

cleanup() {
    local exit_code=$?
    set +e

    log "stopping consumer before cleanup to avoid DB delete/write race"
    run docker-compose -f "${COMPOSE_FILE}" stop "${CONSUMER_SERVICE}" >/dev/null

    log "tearing down ${COUPON_CODE}"
    if [ "${DRY_RUN}" = "true" ]; then
        printf '+ api_post %q %q\n' "/api/v1/admin/load-test/teardown-phase3" "{\"couponCode\":\"${COUPON_CODE}\"}"
    else
        api_post "/api/v1/admin/load-test/teardown-phase3" "{\"couponCode\":\"${COUPON_CODE}\"}" >/dev/null || true
    fi

    log "starting consumer after cleanup"
    run docker-compose -f "${COMPOSE_FILE}" start "${CONSUMER_SERVICE}" >/dev/null

    exit "${exit_code}"
}

trap cleanup EXIT

mkdir -p "$(dirname "${SUMMARY_EXPORT}")" "$(dirname "${LOG_FILE}")"
: > "${LOG_FILE}"

log "starting consumer for measurement"
run docker-compose -f "${COMPOSE_FILE}" start "${CONSUMER_SERVICE}" >/dev/null

if [ "${DRY_RUN}" != "true" ] && ! curl -fsS "${BASE_URL}/actuator/health" >/dev/null; then
    log "API health check failed: ${BASE_URL}/actuator/health"
    exit 1
fi

log "running P3-1 admission with consumer running"
if [ "${DRY_RUN}" = "true" ]; then
    run k6 run \
        --out "influxdb=${INFLUX_URL}" \
        --summary-export "${SUMMARY_EXPORT}" \
        -e BASE_URL="${BASE_URL}" \
        -e VUS="${VUS}" \
        -e STOCK="${STOCK}" \
        -e COUPON_CODE="${COUPON_CODE}" \
        -e DURATION="${DURATION}" \
        -e RAMP_UP="${RAMP_UP}" \
        -e TEARDOWN_MODE=verify-only \
        k6/phase3-admission.js
else
    set -o pipefail
    k6 run \
        --out "influxdb=${INFLUX_URL}" \
        --summary-export "${SUMMARY_EXPORT}" \
        -e BASE_URL="${BASE_URL}" \
        -e VUS="${VUS}" \
        -e STOCK="${STOCK}" \
        -e COUPON_CODE="${COUPON_CODE}" \
        -e DURATION="${DURATION}" \
        -e RAMP_UP="${RAMP_UP}" \
        -e TEARDOWN_MODE=verify-only \
        k6/phase3-admission.js 2>&1 | tee -a "${LOG_FILE}"
fi
