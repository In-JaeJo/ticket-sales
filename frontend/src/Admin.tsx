import { useEffect, useState } from 'react';
import { clock, CrowdState, request, Stats, STATUS_LABEL, useEventSource } from './api';

const MODES: [string, string][] = [
  ['NO_LOCK', '① 락 없음'],
  ['CONDITIONAL_UPDATE', '② 조건부 UPDATE'],
  ['PESSIMISTIC_LOCK', '③ 비관적 락'],
  ['OPTIMISTIC_LOCK', '④ 낙관적 락'],
  ['REDIS_LUA', '⑤ Redis Lua'],
];

const post = (path: string, body?: unknown) => request('POST', path, { body });

/**
 * 관제 화면: 0.5초마다 오는 현황(SSE)을 보여 준다.
 * 위는 이 프로젝트의 주장(결과 지표)과 처리 흐름, 아래 줄은 데모 시작과 기록이다.
 * 장애 버튼은 실험을 켰을 때만 흐름의 해당 단계 아래에 나타난다.
 * 화면 전체 배치(.layout) 안에 들어가므로 위쪽과 아래 줄을 각각 그리드 칸으로 돌려준다.
 */
export default function Admin({ lab }: { lab: boolean }) {
  const [stats, setStats] = useState<Stats | null>(null);
  const [logs, setLogs] = useState<string[]>([]);

  useEventSource('/api/demo/stream', {
    snapshot: (d) => {
      setStats(d.stats);
      setLogs(d.logs);
    },
    stats: (d) => setStats(d),
    log: (line) => setLogs((prev) => [...prev.slice(-19), line]),
  });

  if (!stats) return <section className="card">관제 데이터를 불러오는 중…</section>;

  return (
    <>
      <div className="stack">
        <FaultBanner s={stats} />
        <Kpis s={stats} />
        <Pipeline s={stats} lab={lab} />
      </div>
      <div className="grid2 bottom">
        <DemoStart s={stats} />
        <section className="card">
          <h2>기록</h2>
          <div className="log">
            {[...logs].reverse().map((line, i) => (
              <div key={`${i}-${line}`}>{line}</div>
            ))}
            {logs.length === 0 && <div>아직 기록이 없어요.</div>}
          </div>
        </section>
      </div>
    </>
  );
}

/** 지금 켜져 있는 장애. 화면 상태가 아니라 서버 현황으로 판단하므로 새로 고쳐도 맞다. */
function activeFaults(s: Stats): string[] {
  const faults: string[] = [];
  if (s.kafkaPaused) faults.push('Kafka 발행 중단');
  if (s.pgDown) faults.push('PG 장애');
  if (s.pgFailureRate > 0) faults.push(`결제 실패율 ${Math.round(s.pgFailureRate * 100)}%`);
  if (s.pgDelayMs > 0) faults.push(`결제 지연 ${(s.pgDelayMs / 1000).toFixed(1)}초`);
  return faults;
}

/** 장애 실험을 꺼 두어도 켜진 장애를 잊지 않게 관제 화면 맨 위에 보여 준다. */
function FaultBanner({ s }: { s: Stats }) {
  const faults = activeFaults(s);
  if (faults.length === 0) return null;
  return (
    <div className="alert">
      <span><b>장애 주입 중</b> · {faults.join(' · ')}</span>
      <button className="btn" onClick={() => post('/api/demo/fault', { kafkaPaused: false, pgFailureRate: 0, pgDelayMs: 0, pgTransientErrors: 0 })}>
        모두 해제
      </button>
    </div>
  );
}

/** 이 프로젝트가 증명하려는 네 가지. 숫자 아래에 근거를 한 줄씩 적는다. */
function Kpis({ s }: { s: Stats }) {
  const waiting = s.outboxUnpublished + s.dlqWaiting;
  return (
    <div className="grid4">
      <div className={`kpi ${s.oversold > 0 ? 'bad' : ''}`}>
        <div className="label">초과 판매</div>
        <div className="value">{s.oversold}건</div>
        <div className="desc">{s.oversold > 0 ? '재고보다 더 팔렸어요' : '재고보다 더 팔리지 않았어요'}</div>
      </div>
      <div className={`kpi ${s.consistent ? '' : 'bad'}`}>
        <div className="label">재고 정합성</div>
        <div className="value">{s.consistent ? '맞음' : '깨짐'}</div>
        <div className="desc">
          완료 {s.completed} + 대기 {s.pending}{s.awaitingRestore > 0 && ` + 복구 대기 ${s.awaitingRestore}`} + 재고 {s.remaining} = {s.total}
        </div>
      </div>
      <div className={`kpi ${waiting > 0 ? 'warn' : ''}`}>
        <div className="label">메시지 유실</div>
        <div className="value">0건</div>
        <div className="desc">아직 처리 전: 보내는 중 {s.outboxUnpublished} · DLQ {s.dlqWaiting}</div>
      </div>
      <div className="kpi">
        <div className="label">중복 처리</div>
        <div className="value">0건</div>
        <div className="desc">같은 메시지를 막은 횟수 {s.duplicatesBlocked}</div>
      </div>
    </div>
  );
}

/**
 * 처리 흐름. 1줄: 단계별 지금 숫자, 2줄: 운영 조치, 3줄: 장애 실험(켰을 때만).
 * 장애 버튼을 그 장애가 일어나는 단계 바로 아래에 두어, 누르면 바로 위 숫자가 바뀌는 게 보이게 한다.
 */
function Pipeline({ s, lab }: { s: Stats; lab: boolean }) {
  const pct = (n: number) => (s.total > 0 ? `${(n / s.total) * 100}%` : '0%');
  const beforeOpen = s.salesStatus === 'BEFORE_OPEN';
  return (
    <section className="card">
      <h2>처리 흐름 <span className="h2-note">손님이 표를 사기까지 거치는 단계와 지금 숫자</span></h2>
      <div className="pipe">
        <div className="stage" style={{ gridColumn: 1 }}>
          <div className="name">① 대기열</div>
          <div className="num">{s.waiting.toLocaleString()}명</div>
          <div className="what">{beforeOpen ? `오픈까지 ${clock(s.openInMs)}` : '줄 서서 기다리는 손님'}</div>
          <div className="foot">입장 {s.cursor.toLocaleString()}명 · 연결 {s.sseConnections.toLocaleString()}</div>
        </div>
        <div className="arrow" style={{ gridColumn: 2 }}>→</div>
        <div className="stage" style={{ gridColumn: 3 }}>
          <div className="name">② 주문 · 재고</div>
          <div className="num">{s.remaining.toLocaleString()} <span className="of">/ {s.total.toLocaleString()}장</span></div>
          <div className="what">남은 재고</div>
          <span className={`badge ${s.salesStatus} stage-badge`}>{STATUS_LABEL[s.salesStatus]}</span>
          <div className="bar">
            <span style={{ width: pct(s.completed), background: 'var(--ok)' }} />
            <span style={{ width: pct(s.pending), background: 'var(--warn)' }} />
          </div>
          <div className="foot" title={`DB 커넥션 ${s.poolActive}/${s.poolMax} 사용 중`}>{MODES.find(([m]) => m === s.mode)?.[1] ?? s.mode} 방식</div>
        </div>
        <div className="arrow" style={{ gridColumn: 4 }}>→</div>
        <div className={`stage ${s.kafkaPaused ? 'hot' : ''}`} style={{ gridColumn: 5 }}>
          <div className="name">③ Outbox → Kafka</div>
          <div className="num">{s.outboxUnpublished}건</div>
          <div className="what">아직 못 보낸 이벤트</div>
          <div className="foot">주문과 함께 DB에 기록</div>
        </div>
        <div className="arrow" style={{ gridColumn: 6 }}>→</div>
        <div className={`stage ${s.pgDown || s.dlqWaiting > 0 ? 'hot' : ''}`} style={{ gridColumn: 7 }}>
          <div className="name">④ 결제</div>
          <div className="num">{s.pending}건</div>
          <div className="what">결제를 기다리는 주문</div>
          <div className="foot">실패하면 3번 재시도</div>
        </div>
        <div className="arrow" style={{ gridColumn: 8 }}>→</div>
        <div className="stage" style={{ gridColumn: 9 }}>
          <div className="name">⑤ 결과</div>
          <div className="num ok">{s.completed}건</div>
          <div className="what">결제 완료</div>
          <div className="foot">취소 {s.cancelled}건{s.paidAfterCancel > 0 ? ` · 결제 후 취소 ${s.paidAfterCancel}` : ''}</div>
        </div>

        <AdmissionControl s={s} />
        <div className="ctl" style={{ gridColumn: 7 }}>
          <div className="cap">운영 조치</div>
          <div className="line">
            DLQ {s.dlqWaiting}건
            <button className="btn" disabled={s.dlqWaiting === 0} onClick={() => post('/api/demo/dlq/replay')}>재처리</button>
          </div>
        </div>

        {lab && <KafkaFaults s={s} />}
        {lab && <PaymentFaults s={s} />}
      </div>
      {lab && <div className="hint lab-hint">빨간 버튼은 그 단계를 일부러 고장 내요. 누른 뒤 바로 위 숫자와 맨 위 지표를 보세요.</div>}
    </section>
  );
}

/** 입장률은 운영 조치라 리셋 없이 바로 바뀐다. */
function AdmissionControl({ s }: { s: Stats }) {
  const [rate, setRate] = useState(s.admissionRate);
  useEffect(() => setRate(s.admissionRate), [s.admissionRate]);
  return (
    <div className="ctl" style={{ gridColumn: 1 }}>
      <div className="cap">운영 조치</div>
      <div className="line">입장률 (초당) <input type="number" min={0} value={rate} onChange={(e) => setRate(Number(e.target.value))} /></div>
      <button className="btn" disabled={rate === s.admissionRate} onClick={() => post('/api/demo/config', { admissionRate: rate })}>입장률 적용</button>
    </div>
  );
}

function KafkaFaults({ s }: { s: Stats }) {
  return (
    <div className="ctl lab" style={{ gridColumn: 5 }}>
      <div className="cap">장애 실험</div>
      <button
        className={`btn fault ${s.kafkaPaused ? 'on' : ''}`}
        title="주문은 계속 받고 이벤트는 Outbox에 쌓였다가, 재개하면 모두 발행돼요"
        onClick={() => post('/api/demo/fault', { kafkaPaused: !s.kafkaPaused })}
      >
        {s.kafkaPaused ? 'Kafka 발행 재개' : 'Kafka 발행 중단'}
      </button>
      <button
        className="btn fault"
        title="최근 메시지를 같은 메시지 ID로 한 번 더 보내요. 맨 위 중복 처리 지표의 막은 횟수가 늘어나요"
        onClick={() => post('/api/demo/fault', { duplicate: true })}
      >
        중복 메시지 주입
      </button>
    </div>
  );
}

function PaymentFaults({ s }: { s: Stats }) {
  const [failure, setFailure] = useState(Math.round(s.pgFailureRate * 100));
  const [delay, setDelay] = useState(s.pgDelayMs / 1000);
  useEffect(() => setFailure(Math.round(s.pgFailureRate * 100)), [s.pgFailureRate]);
  useEffect(() => setDelay(s.pgDelayMs / 1000), [s.pgDelayMs]);
  const sendFailure = () => post('/api/demo/fault', { pgFailureRate: failure / 100 });
  const sendDelay = () => post('/api/demo/fault', { pgDelayMs: Math.round(delay * 1000) });
  return (
    <div className="ctl lab" style={{ gridColumn: 7 }}>
      <div className="cap">장애 실험</div>
      <button
        className={`btn fault ${s.pgDown ? 'on' : ''}`}
        title="결제가 계속 실패해 3번 재시도한 뒤 DLQ로 가요. 해소한 뒤 재처리를 누르면 결제가 끝나요"
        onClick={() => post('/api/demo/fault', { pgTransientErrors: s.pgDown ? 0 : 1_000_000 })}
      >
        {s.pgDown ? 'PG 장애 해소' : 'PG 장애 (DLQ로)'}
      </button>
      <div className="line slide" title="PG가 결제를 거절하는 비율. 거절되면 주문이 취소되고 재고가 돌아와요">
        <span>실패율</span>
        <input type="range" min={0} max={100} step={5} value={failure}
          onChange={(e) => setFailure(Number(e.target.value))} onPointerUp={sendFailure} onKeyUp={sendFailure} />
        <b>{failure}%</b>
      </div>
      <div className="line slide" title="결제가 느려져도 주문 응답은 그대로예요. 결제 대기가 쌓였다가 줄어요">
        <span>지연</span>
        <input type="range" min={0} max={2} step={0.1} value={delay}
          onChange={(e) => setDelay(Number(e.target.value))} onPointerUp={sendDelay} onKeyUp={sendDelay} />
        <b>{delay.toFixed(1)}초</b>
      </div>
    </div>
  );
}

const CROWD_LABEL: Record<CrowdState, string> = {
  IDLE: '대기',
  REQUESTED: '투입 요청됨',
  RUNNING: '투입 중',
  DONE: '끝남',
};

/**
 * 데모 시작: 방식·재고를 정하고 리셋한 뒤 가상 손님을 투입한다(ARCHITECTURE.md 6장).
 * 앱은 투입 요청만 적고, 실행기 컨테이너가 받아 k6를 띄운다. 손님 수가 0이면 리셋만 하고 바로 오픈한다.
 */
function DemoStart({ s }: { s: Stats }) {
  const [mode, setMode] = useState(s.mode);
  const [stock, setStock] = useState(s.total);
  const [users, setUsers] = useState(10_000);
  const [openIn, setOpenIn] = useState(30);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => setMode(s.mode), [s.mode]);
  useEffect(() => setStock(s.total), [s.total]);

  const active = s.crowdState === 'REQUESTED' || s.crowdState === 'RUNNING';
  const label = s.crowdRunnerOnline ? CROWD_LABEL[s.crowdState] : '실행기 연결 안 됨';

  async function start() {
    setBusy(true);
    setError(null);
    const results = [await post('/api/demo/config', { initialStock: stock })];
    // 방식 전환은 리셋과 함께만 한다
    if (mode !== s.mode) results.push(await post('/api/demo/mode', { mode }));
    if (users > 0) results.push(await post('/api/demo/crowd', { users, arrivalSeconds: 3, openInSeconds: openIn }));
    else if (mode === s.mode) results.push(await post('/api/demo/reset'));
    const failed = results.find((r) => r.status >= 400);
    if (failed) setError(failed.body?.message ?? `시작하지 못했어요 (${failed.status})`);
    setBusy(false);
  }

  return (
    <section className="card">
      <h2>
        데모 시작
        <span className={`badge ${s.crowdRunnerOnline ? (active ? 'ON_SALE' : '') : 'FINAL_SOLD_OUT'}`}>가상 손님 {label}</span>
      </h2>
      <div className="fields2">
        <label className="field">
          재고 차감 방식
          <select value={mode} onChange={(e) => setMode(e.target.value)}>
            {MODES.map(([value, name]) => <option key={value} value={value}>{name}</option>)}
          </select>
        </label>
        <label className="field">재고 수 <input type="number" min={0} value={stock} onChange={(e) => setStock(Number(e.target.value))} /></label>
        <label className="field">가상 손님 수 <input type="number" min={0} max={10_000} value={users} onChange={(e) => setUsers(Number(e.target.value))} /></label>
        <label className="field">오픈까지 (초) <input type="number" min={0} max={3600} value={openIn} onChange={(e) => setOpenIn(Number(e.target.value))} /></label>
      </div>
      <div className="btns" style={{ marginTop: 8 }}>
        <button className="btn primary" style={{ flex: 1 }} onClick={start} disabled={busy || (users > 0 && !s.crowdRunnerOnline)}>
          {busy ? '시작하는 중…' : '리셋하고 시작'}
        </button>
        <button className="btn" onClick={() => post('/api/demo/crowd/stop')} disabled={!active}>중지</button>
      </div>
      {error && <div className="notice">{error}</div>}
      <div className="hint" style={{ marginTop: 6 }}>
        주문과 대기열을 비우고 처음부터 다시 시작해요. 손님 수가 0이면 리셋만 하고 바로 오픈해요.
        {!s.crowdRunnerOnline && ' 실행기가 꺼져 있어요: docker compose up -d crowd-runner'}
      </div>
    </section>
  );
}
