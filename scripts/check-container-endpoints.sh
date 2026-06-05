#!/usr/bin/env bash
# check-container-endpoints.sh
# Starts the Proxera container, waits for it to be healthy, checks both ports,
# and writes a markdown report.
#
# Usage: check-container-endpoints.sh <image> <proxy-port> <report-file> <report-title>

set -euo pipefail

IMAGE="${1:?First argument must be the Docker image name}"
PROXY_PORT="${2:-18080}"
REPORT_FILE="${3:-container-endpoint-check.md}"
REPORT_TITLE="${4:-Container Endpoint Check}"

STARTUP_WAIT=30
CONTAINER_ID=""

cleanup() {
  if [ -n "$CONTAINER_ID" ]; then
    docker stop "$CONTAINER_ID" >/dev/null 2>&1 || true
    docker rm  "$CONTAINER_ID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "Starting container: $IMAGE"
CONTAINER_ID=$(docker run -d \
  -p "${PROXY_PORT}:8080" \
  -e SPRING_PROFILES_ACTIVE=dev \
  "$IMAGE")

echo "Container ID: $CONTAINER_ID — waiting ${STARTUP_WAIT}s for startup..."
sleep "$STARTUP_WAIT"

check_endpoint() {
  local label="$1"
  local url="$2"
  local start
  start=$(date +%s%3N)
  local http_code
  http_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$url" 2>/dev/null || echo "000")
  local end
  end=$(date +%s%3N)
  local latency=$(( end - start ))
  echo "${label}|${url}|${http_code}|${latency}ms"
}

PROXY_HEALTH=$(check_endpoint "Proxy Health"    "http://localhost:${PROXY_PORT}/actuator/health")
ADMIN_HEALTH=$(check_endpoint "Admin Health"    "http://localhost:${PROXY_PORT}/actuator/health")
ADMIN_LOGIN=$(check_endpoint  "Admin Login"     "http://localhost:${PROXY_PORT}/login")
ADMIN_UI=$(check_endpoint     "Admin Dashboard" "http://localhost:${PROXY_PORT}/admin/")

ALL_RESULTS="$PROXY_HEALTH
$ADMIN_HEALTH
$ADMIN_LOGIN
$ADMIN_UI"

# Build markdown report
{
  echo "## ${REPORT_TITLE}"
  echo
  echo "Image: \`${IMAGE}\`"
  echo
  echo "| Endpoint | URL | Status | Latency |"
  echo "|----------|-----|--------|---------|"
  while IFS='|' read -r label url code latency; do
    if [[ "$code" =~ ^[23] ]]; then
      icon="✅"
    else
      icon="❌"
    fi
    echo "| $icon $label | $url | $code | $latency |"
  done <<< "$ALL_RESULTS"
  echo
  echo "Container logs (last 50 lines):"
  echo '```'
  docker logs --tail 50 "$CONTAINER_ID" 2>&1 || true
  echo '```'
  echo
  echo "Container state:"
  echo '```json'
  docker inspect "$CONTAINER_ID" --format '{{json .State}}' 2>&1 || true
  echo '```'
} > "$REPORT_FILE"

echo "Report written to $REPORT_FILE"
cat "$REPORT_FILE"

# Fail if any critical endpoint returned non-2xx/3xx
FAILED=0
while IFS='|' read -r label url code latency; do
  if ! [[ "$code" =~ ^[23] ]]; then
    echo "FAILED: $label returned $code ($url)" >&2
    FAILED=1
  fi
done <<< "$PROXY_HEALTH
$ADMIN_HEALTH"

exit "$FAILED"
