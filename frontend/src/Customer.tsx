import { useEffect, useRef, useState } from 'react';
import { clock, request, SalesStatus, STATUS_LABEL, useEventSource } from './api';

/** 손님 화면 상태 (ARCHITECTURE.md 3장의 흐름) */
type Phase =
  | { kind: 'idle' }
  | { kind: 'waiting'; ticket: number; startPosition: number }
  | { kind: 'admitted'; until: number }
  | { kind: 'paying'; orderId: number }
  | { kind: 'done'; result: 'completed' | 'cancelled' | 'soldOut' | 'duplicate' | 'noPass' | 'saleEnded' | 'busy' };

const PASS_SECONDS = 180;

/** 브라우저 탭마다 다른 손님. 창을 두 개 띄우면 손님 두 명이다. */
function newUserId() {
  return `me-${Math.random().toString(36).slice(2, 7)}`;
}

function initialUserId() {
  try {
    const saved = sessionStorage.getItem('userId');
    if (saved) return saved;
  } catch {
    // 저장소를 못 쓰면 새로 만든다
  }
  return newUserId();
}

export default function Customer() {
  const [userId, setUserId] = useState(initialUserId);
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const [cursor, setCursor] = useState(0);
  const [status, setStatus] = useState<SalesStatus>('ON_SALE');
  // 오픈 시각. 서버가 보낸 "남은 시간"을 내 시계에 더해 정하므로 PC 시계가 틀려도 어긋나지 않는다
  const [openAt, setOpenAt] = useState(0);
  const [message, setMessage] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now());
  const phaseRef = useRef(phase);
  phaseRef.current = phase;

  useEffect(() => {
    try {
      sessionStorage.setItem('userId', userId);
    } catch {
      // 무시
    }
  }, [userId]);

  // 대기열 SSE: 커서 숫자 하나와 판매 상태만 받는다
  useEventSource('/api/queue/stream', {
    snapshot: (d) => {
      setCursor(d.cursor);
      setStatus(d.status);
      setOpenAt(Date.now() + d.openInMs);
    },
    cursor: (d) => setCursor(d.cursor),
    status: (d) => setStatus(d.status),
    reset: (d) => {
      setCursor(d.cursor);
      setStatus(d.status);
      setOpenAt(Date.now() + d.openInMs);
      setPhase({ kind: 'idle' });
      setMessage('리셋됐어요. 처음부터 다시 줄을 설 수 있어요.');
    },
  });

  // 내 차례가 오면 입장
  useEffect(() => {
    if (phase.kind === 'waiting' && cursor >= phase.ticket) {
      setPhase({ kind: 'admitted', until: Date.now() + PASS_SECONDS * 1000 });
    }
  }, [cursor, phase]);

  // 입장 타이머, 오픈 카운트다운
  useEffect(() => {
    if (phase.kind !== 'admitted' && status !== 'BEFORE_OPEN') return;
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, [phase.kind, status]);

  // 결제 결과 확인
  useEffect(() => {
    if (phase.kind !== 'paying') return;
    const timer = setInterval(async () => {
      const res = await request('GET', `/api/orders/${phase.orderId}`);
      if (res.body?.status === 'COMPLETED') setPhase({ kind: 'done', result: 'completed' });
      if (res.body?.status === 'CANCELLED') setPhase({ kind: 'done', result: 'cancelled' });
    }, 500);
    return () => clearInterval(timer);
  }, [phase]);

  async function enter() {
    setMessage(null);
    const res = await request('POST', '/api/queue/enter', { userId });
    if (res.status === 200 && res.body.admitted) {
      setPhase({ kind: 'admitted', until: Date.now() + PASS_SECONDS * 1000 });
    } else if (res.status === 200) {
      const ticket = res.body.ticket as number;
      setPhase({ kind: 'waiting', ticket, startPosition: Math.max(1, ticket - cursor) });
    } else if (res.body?.code === 'ALREADY_ORDERED') {
      setPhase({ kind: 'done', result: 'duplicate' });
    } else if (res.body?.code === 'NOT_OPEN') {
      setMessage('아직 오픈 전이에요. 정각에 다시 눌러 주세요.');
    } else if (res.body?.code === 'SALE_ENDED') {
      setPhase({ kind: 'done', result: 'saleEnded' });
    } else {
      setMessage(res.body?.message ?? `줄 서기 실패 (${res.status})`);
    }
  }

  async function leave() {
    await request('DELETE', '/api/queue/leave', { userId });
    setPhase({ kind: 'idle' });
  }

  async function buy() {
    const res = await request('POST', '/api/orders', { userId, body: { productId: 1 } });
    if (res.status === 200) return setPhase({ kind: 'paying', orderId: res.body.orderId });
    const code = res.body?.code;
    if (code === 'SOLD_OUT') return setPhase({ kind: 'done', result: 'soldOut' });
    if (code === 'DUPLICATE') return setPhase({ kind: 'done', result: 'duplicate' });
    if (code === 'NO_PASS') return setPhase({ kind: 'done', result: 'noPass' });
    if (res.status === 503) return setPhase({ kind: 'done', result: 'busy' });
    setMessage(res.body?.message ?? `주문 실패 (${res.status})`);
  }

  function anotherCustomer() {
    setUserId(newUserId());
    setPhase({ kind: 'idle' });
    setMessage(null);
  }

  return (
    <section className="card customer">
      <h2>
        손님 화면 <span className="muted">{userId}</span>
      </h2>
      <div className="muted" style={{ textAlign: 'center' }}>
        공연 티켓 · 50,000원 <span className={`badge ${status}`}>{STATUS_LABEL[status]}</span>
      </div>
      <div className="screen">{renderPhase()}</div>
      {message && <div className="notice">{message}</div>}
      <div className="btns" style={{ marginTop: 10, justifyContent: 'center' }}>
        <button className="btn" onClick={anotherCustomer}>새 손님으로</button>
      </div>
    </section>
  );

  function renderPhase() {
    switch (phase.kind) {
      case 'idle':
        if (status === 'BEFORE_OPEN') {
          return (
            <>
              <div className="title">곧 오픈해요</div>
              <div className="hint">정각이 되면 줄 서기 버튼이 열려요</div>
              <div className="big">{clock(openAt - now)}</div>
              <button className="btn primary block" style={{ marginTop: 16 }} disabled>오픈 전</button>
            </>
          );
        }
        return (
          <>
            <div className="title">선착순 판매</div>
            <div className="hint">줄을 서면 번호표를 받고, 차례가 되면 입장해요.</div>
            <button className="btn primary block" style={{ marginTop: 16 }} onClick={enter} disabled={status === 'FINAL_SOLD_OUT'}>
              {status === 'FINAL_SOLD_OUT' ? '판매가 끝났어요' : '줄 서기'}
            </button>
          </>
        );
      case 'waiting': {
        const position = Math.max(0, phase.ticket - cursor);
        const progress = 1 - position / phase.startPosition;
        return (
          <>
            <div className="hint">내 번호표</div>
            <div className="ticket">#{phase.ticket.toLocaleString()}</div>
            <div className="hint">앞에 남은 사람</div>
            <div className="big">{position.toLocaleString()}명</div>
            <div className="bar" style={{ width: '100%' }}>
              <span style={{ width: `${Math.max(0, Math.min(1, progress)) * 100}%`, background: 'var(--accent)' }} />
            </div>
            {status === 'SOLD_OUT' && <div className="hint">매진됐어요. 취소표가 나오면 입장이 다시 시작돼요. 순번은 그대로예요.</div>}
            {status === 'FINAL_SOLD_OUT' && <div className="title bad">판매가 끝났어요</div>}
            <button className="btn" onClick={leave} style={{ marginTop: 8 }}>줄에서 나가기</button>
          </>
        );
      }
      case 'admitted': {
        const left = Math.max(0, Math.round((phase.until - now) / 1000));
        return (
          <>
            <div className="title accent">입장했어요</div>
            <div className="hint">3분 안에 구매해 주세요</div>
            <div className="big">{Math.floor(left / 60)}:{String(left % 60).padStart(2, '0')}</div>
            <button className="btn primary block" style={{ marginTop: 12 }} onClick={buy} disabled={left === 0}>
              구매하기
            </button>
          </>
        );
      }
      case 'paying':
        return (
          <>
            <div className="title">결제 중</div>
            <div className="hint">주문 #{phase.orderId} · 결제 결과를 기다리고 있어요</div>
          </>
        );
      case 'done':
        return <Result result={phase.result} onRetry={enter} />;
    }
  }
}

function Result({ result, onRetry }: { result: Extract<Phase, { kind: 'done' }>['result']; onRetry: () => void }) {
  const views = {
    completed: ['구매 완료', 'ok', '티켓 1장을 샀어요.'],
    cancelled: ['주문 취소', 'bad', '결제에 실패해 주문이 취소됐어요. 티켓은 다시 판매돼요.'],
    soldOut: ['매진', 'bad', '입장했지만 그사이 표가 모두 팔렸어요.'],
    duplicate: ['이미 주문함', 'bad', '1인 1회만 주문할 수 있어요. 결제에 실패해 취소된 주문도 포함돼요.'],
    noPass: ['입장 시간이 지났어요', 'bad', '다시 줄을 서 주세요.'],
    saleEnded: ['판매 종료', 'bad', '더 판매할 표가 없어요.'],
    busy: ['주문이 몰리고 있어요', 'bad', '잠시 후 다시 줄을 서 주세요.'],
  } as const;
  const [title, tone, hint] = views[result];
  const canRetry = result === 'noPass' || result === 'busy';
  return (
    <>
      <div className={`title ${tone}`}>{title}</div>
      <div className="hint">{hint}</div>
      {canRetry && (
        <button className="btn primary" style={{ marginTop: 12 }} onClick={onRetry}>다시 줄 서기</button>
      )}
    </>
  );
}
