#!/bin/sh
# 가상 손님 실행기. 관제 화면의 투입 요청을 1초마다 확인해 자기 안에서 k6를 실행·중지한다.
# 앱은 Redis에 요청만 적고 Docker를 건드리지 않는다(ARCHITECTURE.md 6장).
#   요청이 새로 생기면  → 실행 중인 k6를 멈추고 새로 실행, RUNNING 보고
#   요청이 사라지면     → k6를 멈춤 (중지 버튼, 리셋)
#   k6가 스스로 끝나면  → DONE 보고
API="${API:-http://nginx}"
current=""
pid=""

# JSON에서 값 하나 꺼내기 (중첩 없는 숫자만)
field() { echo "$2" | sed -E "s/.*\"$1\":([0-9]+).*/\1/"; }

report() {
  wget -q -O /dev/null --header 'Content-Type: application/json' \
    --post-data "{\"id\":$1,\"state\":\"$2\"}" "$API/api/demo/crowd/state" || true
}

stop_k6() {
  if [ -n "$pid" ]; then
    kill "$pid" 2> /dev/null
    wait "$pid" 2> /dev/null
    echo "가상 손님 중지 (투입 $current)"
    pid=""
  fi
}

trap 'stop_k6; exit 0' TERM INT

echo "가상 손님 실행기 시작: $API 의 투입 요청을 기다리는 중"
while :; do
  # 앱이 응답하지 않으면 지금 상태를 그대로 둔다. 204(요청 없음)는 빈 본문으로 성공한다.
  if body="$(wget -q -O - "$API/api/demo/crowd/request" 2> /dev/null)"; then
    id=""
    [ -n "$body" ] && id="$(field id "$body")"
    if [ "$id" != "$current" ]; then
      stop_k6
      current="$id"
      if [ -n "$id" ]; then
        users="$(field users "$body")"
        arrival="$(field arrivalSeconds "$body")"
        open_at="$(field openAt "$body")"
        echo "가상 손님 ${users}명 투입 (투입 $id, 도착 ${arrival}초)"
        k6 run --quiet -e BASE_URL="$API" -e USERS="$users" -e PREFIX="crowd-$id" -e CROWD_ID="$id" -e ARRIVAL_SECONDS="$arrival" -e OPEN_AT="$open_at" \
          /scripts/open.js > /dev/null 2>&1 &
        pid=$!
        report "$id" RUNNING
      fi
    elif [ -n "$pid" ] && ! kill -0 "$pid" 2> /dev/null; then
      wait "$pid" 2> /dev/null
      pid=""
      echo "가상 손님 모두 끝남 (투입 $current)"
      report "$current" DONE
    fi
  fi
  sleep 1
done
