#!/usr/bin/env bash
# ticket-sales 데모·측정 스크립트
#
#   ./demo.sh            전체 기동 → 5가지 방식 비교 측정 → 결과표 출력
#   ./demo.sh up         전체 기동하고 준비될 때까지 기다림
#   ./demo.sh compare    재고 차감 5가지 방식을 같은 조건에서 연속 측정
#   ./demo.sh open       오픈 상황 측정: 정각에 1만 명이 2초 안에 대기열로 몰릴 때 입장률별 응답 시간·오류율
#   ./demo.sh crowd      가상 손님을 대기열에 몰아넣음 (손님 화면으로 함께 줄 서기)
#   ./demo.sh crowd stop 가상 손님 중지
#   ./demo.sh failure    장애 데모: Kafka 중단 → 복구, 중복 메시지, 결제 실패 → 보상, DLQ 재처리
#
# 값은 환경 변수로 바꾼다. 예: USERS=3000 STOCK=500 ./demo.sh compare
#   crowd 전용: RATE=입장률(초당), OPEN=오픈까지(초), ARRIVAL=오픈 후 손님이 몰려드는 시간(초)
#   open 전용: RATES="입장률 목록", ARRIVAL=정각 뒤 손님이 몰려드는 시간(초)
set -euo pipefail
cd "$(dirname "$0")"

API="${API:-http://localhost}"
MODES=(NO_LOCK CONDITIONAL_UPDATE PESSIMISTIC_LOCK OPTIMISTIC_LOCK REDIS_LUA)

# ---------------------------------------------------------------- 공통

say()  { printf '\n\033[1m▶ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✔\033[0m %s\n' "$*"; }
fail() { printf '  \033[31m✘\033[0m %s\n' "$*"; exit 1; }

post() { curl -fsS -X POST -H 'Content-Type: application/json' "$API$1" ${2:+-d "$2"} > /dev/null; }
stats() { curl -fsS "$API/api/demo/stats"; }
order() { curl -fsS -X POST -H "Content-Type: application/json" -H "X-User-Id: $1" "$API/api/orders" -d "{\"productId\":1}" > /dev/null; }
# JSON에서 값 하나 꺼내기 (jq 없이). 중첩 없는 숫자·불리언·문자열만 다룬다.
field() { sed -E "s/.*\"$1\":\"?([^,\"}]*)\"?.*/\1/" <<< "$(tr -d '\n ' <<< "$2")"; }

user_ids() { seq 1 "$2" | sed "s/^/\"$1-/; s/\$/\"/" | paste -sd, -; }

wait_until() { # 조건 명령, 최대 초
  local i
  for ((i = 0; i < $2; i++)); do eval "$1" && return 0; sleep 1; done
  return 1
}

no_pending() { [ "$(field pending "$(stats)")" = "0" ]; }

print_stats() {
  local s; s="$(stats)"
  printf '  재고 %s/%s · 완료 %s · 결제 대기 %s · 취소 %s · 초과 판매 %s · 정합성 %s · Outbox 미발행 %s · DLQ %s\n' \
    "$(field remaining "$s")" "$(field total "$s")" "$(field completed "$s")" "$(field pending "$s")" \
    "$(field cancelled "$s")" "$(field oversold "$s")" "$(field consistent "$s")" "$(field outboxUnpublished "$s")" "$(field dlqWaiting "$s")"
}

# ---------------------------------------------------------------- up

up() {
  say "전체 기동"
  docker compose up -d --build
  wait_until "curl -fsS $API/api/demo/stats > /dev/null 2>&1" 180 || fail "앱이 3분 안에 준비되지 않았어요. docker compose logs app 을 확인하세요."
  ok "준비 완료"
  printf '  API %s/api · API 문서 %s/swagger-ui · Grafana http://localhost:3000 · Kafka UI http://localhost:8081\n' "$API" "$API"
}

# ---------------------------------------------------------------- compare

compare() {
  local users="${USERS:-2000}" stock="${STOCK:-500}"
  local table="k6/results/compare.md"
  mkdir -p k6/results
  say "방식 비교: 가상 손님 ${users}명이 동시에 한 번씩 주문, 재고 ${stock}장"
  {
    echo "| 방식 | TPS | p50 | p95 | p99 | 성공 | 품절 | 혼잡 | 초과 판매 | 낙관적 락 충돌 | 정합성 |"
    echo "|---|---|---|---|---|---|---|---|---|---|---|"
  } > "$table"

  for mode in "${MODES[@]}"; do
    post /api/demo/config "{\"initialStock\":$stock,\"admissionRate\":0}"
    post /api/demo/fault '{"pgFailureRate":0.0,"pgDelayMs":0,"kafkaPaused":false}'
    post /api/demo/mode "{\"mode\":\"$mode\"}"   # 방식 전환은 리셋과 함께
    post /api/demo/pass "{\"userIds\":[$(user_ids "cmp-$mode" "$users")]}"
    local retries_before; retries_before="$(field optimisticRetries "$(stats)")"

    docker compose --profile load run --rm -e MODE="$mode" -e USERS="$users" -e PREFIX="cmp-$mode" \
      k6 run --quiet /scripts/compare.js

    wait_until no_pending 60 || true
    local s r
    s="$(stats)"
    r="$(cat "k6/results/$mode.json")"
    local retries; retries=$(awk "BEGIN { printf \"%d\", $(field optimisticRetries "$s") - $retries_before }")
    printf '| %s | %.0f | %.0fms | %.0fms | %.0fms | %s | %s | %s | %s | %s | %s |\n' \
      "$mode" "$(field tps "$r")" "$(field p50 "$r")" "$(field p95 "$r")" "$(field p99 "$r")" \
      "$(field ok "$r")" "$(field soldOut "$r")" "$(field busy "$r")" \
      "$(field oversold "$s")" "$retries" "$(field consistent "$s")" >> "$table"
  done

  post /api/demo/mode '{"mode":"REDIS_LUA"}'
  say "결과 ($table)"
  cat "$table"
}

# ---------------------------------------------------------------- open

open_measure() {
  local users="${USERS:-10000}" stock="${STOCK:-1000}" arrival="${ARRIVAL:-2}" rates="${RATES:-100 500 2000}"
  local table="k6/results/open.md"
  mkdir -p k6/results
  say "오픈 상황: 가상 손님 ${users}명이 ${arrival}초 동안 몰려듦, 재고 ${stock}장, 입장률 ${rates}"
  {
    echo "| 입장률(초당) | 최대 동시 대기 | 최대 SSE 연결 | 줄 서기 p95 | 줄 서기 p99 | 주문 p95 | 입장까지 대기 p95 | 구매 | 입장 후 품절 | 판매 종료 안내 | 5xx·연결 오류 | 초과 판매 | 정합성 |"
    echo "|---|---|---|---|---|---|---|---|---|---|---|---|---|"
  } > "$table"

  local rate
  for rate in $rates; do
    post /api/demo/config "{\"initialStock\":$stock,\"admissionRate\":$rate}"
    post /api/demo/fault '{"pgFailureRate":0.0,"pgDelayMs":0,"pgTransientErrors":0,"kafkaPaused":false}'
    post /api/demo/mode '{"mode":"REDIS_LUA"}'

    # 측정 중 최대 동시 대기·SSE 연결 수를 0.5초마다 기록
    local peak="k6/results/open-peak-$rate.txt"
    : > "$peak"
    ( while :; do
        s="$(stats 2>/dev/null)" && echo "$(field waiting "$s") $(field sseConnections "$s")" >> "$peak"
        sleep 0.5
      done ) &
    local sampler=$!

    docker compose --profile load run --rm -e USERS="$users" -e PREFIX="open-$rate" -e ARRIVAL_SECONDS="$arrival" \
      -e RESULT="open-$rate" k6 run --quiet /scripts/open.js
    kill "$sampler" 2> /dev/null; wait "$sampler" 2> /dev/null || true

    wait_until no_pending 60 || true
    local s r maxWaiting maxSse
    s="$(stats)"
    r="$(cat "k6/results/open-$rate.json")"
    maxWaiting=$(awk 'BEGIN { m = 0 } $1 > m { m = $1 } END { print m }' "$peak")
    maxSse=$(awk 'BEGIN { m = 0 } $2 > m { m = $2 } END { print m }' "$peak")
    rm -f "$peak"
    printf '| %s | %s | %s | %.0fms | %.0fms | %.0fms | %.1f초 | %s | %s | %s | %s | %s | %s |\n' \
      "$rate" "$maxWaiting" "$maxSse" "$(field enterP95 "$r")" "$(field enterP99 "$r")" "$(field orderP95 "$r")" \
      "$(awk "BEGIN { print $(field waitP95 "$r") / 1000 }")" "$(field bought "$r")" "$(field soldOut "$r")" "$(field saleEnded "$r")" \
      "$(field failedRequests "$r")" "$(field oversold "$s")" "$(field consistent "$s")" >> "$table"
  done

  say "결과 ($table)"
  cat "$table"
}

# ---------------------------------------------------------------- crowd

crowd() {
  if [ "${1:-}" = "stop" ]; then
    post /api/demo/crowd/stop
    ok "가상 손님 중지"
    return
  fi
  local users="${USERS:-2000}" stock="${STOCK:-100}" rate="${RATE:-100}" arrival="${ARRIVAL:-10}" open="${OPEN:-0}"
  say "가상 손님 ${users}명 투입 (재고 ${stock}장, 입장률 초당 ${rate}명, 오픈까지 ${open}초, 오픈 후 도착 ${arrival}초)"
  post /api/demo/config "{\"initialStock\":$stock,\"admissionRate\":$rate}"
  post /api/demo/crowd "{\"users\":$users,\"arrivalSeconds\":$arrival,\"openInSeconds\":$open}"   # 실행기 컨테이너가 받아 k6를 띄운다
  wait_until 'case "$(field crowdState "$(stats)")" in RUNNING|DONE) true ;; *) false ;; esac' 10 \
    || fail "실행기가 요청을 받지 않았어요. docker compose logs crowd-runner 를 확인하세요."
  ok "가상 손님이 대기열에 몰려드는 중이에요. 손님 화면에서 함께 줄 서 보세요."
  printf '  관제 화면의 데모 시작 카드에서도 투입·중지할 수 있어요. 중지: ./demo.sh crowd stop\n'
}

# ---------------------------------------------------------------- failure

failure() {
  say "장애 데모 준비: 재고 10장, 손님 5명에게 입장 토큰"
  post /api/demo/config '{"initialStock":10,"admissionRate":0}'
  post /api/demo/fault '{"pgFailureRate":0.0,"pgDelayMs":0,"pgTransientErrors":0,"kafkaPaused":false}'
  post /api/demo/mode '{"mode":"REDIS_LUA"}'
  post /api/demo/pass "{\"userIds\":[$(user_ids f 9)]}"

  say "1. Kafka를 멈추고 주문 5건"
  docker compose stop kafka > /dev/null
  for i in 1 2 3 4 5; do order "f-$i"; done
  sleep 2
  print_stats
  [ "$(field outboxUnpublished "$(stats)")" -ge 1 ] && ok "주문은 받았고, 이벤트는 Outbox에 쌓여 있어요" || fail "Outbox에 쌓이지 않았어요"

  say "2. Kafka 재시작"
  docker compose start kafka > /dev/null
  wait_until '[ "$(field outboxUnpublished "$(stats)")" = "0" ] && [ "$(field completed "$(stats)")" = "5" ]' 120 \
    || fail "2분 안에 복구되지 않았어요"
  print_stats
  ok "쌓였던 이벤트가 모두 발행돼 5건 모두 결제 완료. 메시지 유실 0"

  say "3. 같은 메시지를 한 번 더 발행"
  local blocked_before
  blocked_before=$(field duplicatesBlocked "$(stats)")
  post /api/demo/fault '{"duplicate":true}'
  wait_until '[ "$(field duplicatesBlocked "$(stats)")" -gt '"$blocked_before"' ]' 15 \
    || fail "같은 메시지가 멱등 키에 걸리지 않았어요"
  print_stats
  ok "같은 메시지 ID라 멱등 키가 막았어요 (막은 중복 $blocked_before → $(field duplicatesBlocked "$(stats)")). 중복 처리 0"

  say "4. 결제 실패율 100% → 주문 취소 → 재고 복구"
  post /api/demo/fault '{"pgFailureRate":1.0}'
  order f-6
  wait_until '[ "$(field cancelled "$(stats)")" = "1" ] && [ "$(field remaining "$(stats)")" = "5" ]' 30 || fail "보상이 끝나지 않았어요"
  print_stats
  ok "결제 실패 → 주문 취소 → 재고 복구"

  say "5. PG 일시 장애가 계속돼 재시도 3번 뒤 DLQ로 → 장애 해소 후 DLQ 재처리"
  post /api/demo/fault '{"pgFailureRate":0.0,"pgTransientErrors":100}'
  order f-7
  wait_until '[ "$(field dlqWaiting "$(stats)")" -ge 1 ]' 60 || fail "1분 안에 DLQ로 가지 않았어요"
  print_stats
  ok "재시도 3번 모두 실패해 DLQ로 이동. 주문은 결제 대기로 남아 있어요"
  post /api/demo/fault '{"pgTransientErrors":0}'
  local replayed="" n
  for n in 1 2 3 4 5 6 7 8 9 10; do
    replayed="$(curl -fsS -X POST "$API/api/demo/dlq/replay")"
    [ "$(field replayed "$replayed")" -ge 1 ] && break
    sleep 1
  done
  wait_until '[ "$(field completed "$(stats)")" = "6" ]' 30 || fail "DLQ 재처리 후에도 완료되지 않았어요"
  print_stats
  ok "DLQ에서 $(field replayed "$replayed")건 재처리 → 결제 완료"

  say "결과"
  local s; s="$(stats)"
  [ "$(field oversold "$s")" = "0" ] && ok "초과 판매 0" || fail "초과 판매 발생"
  [ "$(field consistent "$s")" = "true" ] && ok "정합성: 완료 + 결제 대기 + 남은 재고 = 전체 재고" || fail "정합성 깨짐"
  ok "메시지 유실 0, 중복 처리 0"
}

# ---------------------------------------------------------------- 진입점

case "${1:-auto}" in
  auto)    up; compare ;;
  up)      up ;;
  compare) compare ;;
  open)    open_measure ;;
  crowd)   crowd "${2:-}" ;;
  failure) failure ;;
  *) echo "사용법: ./demo.sh [up|compare|open|crowd [stop]|failure]"; exit 1 ;;
esac
