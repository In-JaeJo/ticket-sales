// 오픈 상황: 가상 손님들이 대기열에 줄 서고, SSE로 커서를 받다가 자기 차례가 되면 주문한다.
// ./demo.sh crowd(화면 시연)와 ./demo.sh open(측정)이 실행한다.
// 손님마다 한 번만 시도한다: 입장해서 주문하거나, 판매 종료를 받으면 끝난다.
import http from 'k6/http';
import sse from 'k6/x/sse';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://nginx';
const USERS = Number(__ENV.USERS || 2000);
const PREFIX = __ENV.PREFIX || 'crowd';
const ARRIVAL_SECONDS = Number(__ENV.ARRIVAL_SECONDS || 10);
const RESULT = __ENV.RESULT || '';
// 실행기가 띄울 때만 있다. 리셋·중지로 이 투입이 끝났으면 새 판에 끼어들지 않는다
const CROWD_ID = __ENV.CROWD_ID || '';
// 오픈 시각(epoch 밀리초). 있으면 그때까지 기다렸다가 몰린다. 실행기와 앱은 같은 컴퓨터의 시계를 쓴다
const OPEN_AT = Number(__ENV.OPEN_AT || 0);
const OPEN_WAIT_SECONDS = Math.max(0, (OPEN_AT - Date.now()) / 1000);

const bought = new Counter('crowd_bought');
const soldOut = new Counter('crowd_sold_out');
const orderFailed = new Counter('crowd_order_failed');
const ended = new Counter('crowd_sale_ended');
const rejected = new Counter('crowd_rejected');
const enterTime = new Trend('crowd_enter_ms', true);
const orderTime = new Trend('crowd_order_ms', true);
const waitTime = new Trend('crowd_wait_ms', true);

// 409(이미 주문함·판매 종료·품절)는 정상 응답이다. 실패율에는 5xx와 연결 오류만 센다
http.setResponseCallback(http.expectedStatuses(200, 409));

export const options = {
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    crowd: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: `${Math.ceil(OPEN_WAIT_SECONDS + ARRIVAL_SECONDS) + 900}s` },
  },
};

export default function () {
  // 오픈 시각까지 기다렸다가, 오픈 직후 ARRIVAL_SECONDS 동안 손님이 고르게 몰려든다
  if (OPEN_AT) sleep(Math.max(0, (OPEN_AT - Date.now()) / 1000));
  sleep(Math.random() * ARRIVAL_SECONDS);

  if (CROWD_ID && !stillRequested()) return;

  const headers = { 'X-User-Id': `${PREFIX}-${__VU}` };
  const enter = http.post(`${BASE}/api/queue/enter`, null, { headers });
  enterTime.add(enter.timings.duration);
  if (enter.status !== 200) {
    if (enter.status === 409) ended.add(1);
    else rejected.add(1);
    return;
  }
  const body = enter.json();
  const queuedAt = Date.now();
  let admitted = body.admitted;
  let saleEnded = false;

  if (!admitted) {
    sse.open(`${BASE}/api/queue/stream`, {}, function (client) {
      client.on('event', function (event) {
        const data = event.data ? JSON.parse(event.data) : {};
        if ((event.name === 'snapshot' || event.name === 'cursor') && data.cursor >= body.ticket) {
          admitted = true;
          client.close();
        }
        if ((event.name === 'snapshot' || event.name === 'status') && data.status === 'FINAL_SOLD_OUT') {
          saleEnded = true;
          client.close();
        }
        // 리셋되면 줄 선 판이 사라졌으므로 끝낸다 (손님 화면도 처음으로 돌아간다)
        if (event.name === 'reset') client.close();
      });
    });
  }

  if (admitted) {
    waitTime.add(Date.now() - queuedAt);
    const res = http.post(`${BASE}/api/orders`, JSON.stringify({ productId: 1 }), {
      headers: Object.assign({ 'Content-Type': 'application/json' }, headers),
    });
    orderTime.add(res.timings.duration);
    if (res.status === 200) bought.add(1);
    else if (res.status === 409) soldOut.add(1);
    else orderFailed.add(1);
  } else if (saleEnded) {
    ended.add(1);
  }
}

function stillRequested() {
  const res = http.get(`${BASE}/api/demo/crowd/request`, { tags: { name: 'crowd-check' } });
  return res.status === 200 && String(res.json().id) === CROWD_ID;
}

export function handleSummary(data) {
  if (!RESULT) return { stdout: '' };
  const m = data.metrics;
  const count = (name) => (m[name] ? m[name].values.count : 0);
  const pct = (name, p) => (m[name] ? m[name].values[`p(${p})`] : 0);
  const reqs = m.http_reqs ? m.http_reqs.values.count : 0;
  const failedReqs = m.http_req_failed ? m.http_req_failed.values.passes : 0;
  const result = {
    users: USERS,
    enterP95: pct('crowd_enter_ms', 95),
    enterP99: pct('crowd_enter_ms', 99),
    orderP95: pct('crowd_order_ms', 95),
    waitP95: pct('crowd_wait_ms', 95),
    bought: count('crowd_bought'),
    soldOut: count('crowd_sold_out'),
    saleEnded: count('crowd_sale_ended'),
    rejected: count('crowd_rejected'),
    orderFailed: count('crowd_order_failed'),
    requests: reqs,
    failedRequests: failedReqs,
  };
  return { [`/results/${RESULT}.json`]: JSON.stringify(result, null, 2), stdout: '' };
}
