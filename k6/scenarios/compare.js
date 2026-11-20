// 방식 비교: 입장 토큰을 미리 받은 가상 손님들이 주문 API를 동시에 한 번씩 호출한다.
// 대기열을 거치지 않는다. 거치면 모든 방식의 TPS가 입장률에 맞춰져 비교가 안 된다.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://nginx';
const USERS = Number(__ENV.USERS || 1000);
const PREFIX = __ENV.PREFIX || 'cmp';
const MODE = __ENV.MODE || 'unknown';

const ok = new Counter('orders_ok');
const soldOut = new Counter('orders_sold_out');
const busy = new Counter('orders_busy');
const other = new Counter('orders_other');

export const options = {
  scenarios: {
    compare: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '3m' },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.post(`${BASE}/api/orders`, JSON.stringify({ productId: 1 }), {
    headers: { 'Content-Type': 'application/json', 'X-User-Id': `${PREFIX}-${__VU}` },
  });
  if (res.status === 200) ok.add(1);
  else if (res.status === 409) soldOut.add(1);
  else if (res.status === 503) busy.add(1);
  else other.add(1);
}

export function handleSummary(data) {
  const m = data.metrics;
  const count = (name) => (m[name] ? m[name].values.count : 0);
  const result = {
    mode: MODE,
    users: USERS,
    tps: m.http_reqs.values.rate,
    p50: m.http_req_duration.values['p(50)'],
    p95: m.http_req_duration.values['p(95)'],
    p99: m.http_req_duration.values['p(99)'],
    ok: count('orders_ok'),
    soldOut: count('orders_sold_out'),
    busy: count('orders_busy'),
    other: count('orders_other'),
  };
  return {
    [`/results/${MODE}.json`]: JSON.stringify(result, null, 2),
    stdout: `${MODE}: TPS ${result.tps.toFixed(0)}, p95 ${result.p95.toFixed(1)}ms, 성공 ${result.ok}, 품절 ${result.soldOut}, 혼잡 ${result.busy}, 기타 ${result.other}\n`,
  };
}
