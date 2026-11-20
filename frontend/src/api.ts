import { useEffect, useRef } from 'react';

export type SalesStatus = 'BEFORE_OPEN' | 'ON_SALE' | 'SOLD_OUT' | 'FINAL_SOLD_OUT';

export const STATUS_LABEL: Record<SalesStatus, string> = {
  BEFORE_OPEN: '오픈 전',
  ON_SALE: '판매 중',
  SOLD_OUT: '매진 · 취소표 대기',
  FINAL_SOLD_OUT: '판매 종료',
};

export type Stats = {
  runId: number;
  mode: string;
  salesStatus: SalesStatus;
  openInMs: number;
  total: number;
  remaining: number;
  pending: number;
  completed: number;
  cancelled: number;
  oversold: number;
  consistent: boolean;
  waiting: number;
  cursor: number;
  sseConnections: number;
  admissionRate: number;
  outboxUnpublished: number;
  dlqWaiting: number;
  duplicatesBlocked: number;
  staleSkipped: number;
  paidAfterCancel: number;
  optimisticRetries: number;
  kafkaPaused: boolean;
  pgFailureRate: number;
  pgDelayMs: number;
  pgDown: boolean;
  poolActive: number;
  poolMax: number;
  crowdState: CrowdState;
  crowdUsers: number;
  crowdArrivalSeconds: number;
  crowdRunnerOnline: boolean;
};

export type CrowdState = 'IDLE' | 'REQUESTED' | 'RUNNING' | 'DONE';

export type ApiResult<T> = { status: number; body: T };

/** 응답 상태와 본문을 함께 돌려준다. 409·403 같은 응답도 화면에서 상태로 다룬다. */
export async function request<T = any>(method: string, path: string, init: { body?: unknown; userId?: string } = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = {};
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';
  if (init.userId) headers['X-User-Id'] = init.userId;
  const res = await fetch(path, { method, headers, body: init.body === undefined ? undefined : JSON.stringify(init.body) });
  const text = await res.text();
  let body: any = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  return { status: res.status, body };
}

/** 남은 밀리초를 m:ss로 */
export function clock(ms: number) {
  const left = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
}

/** JSON이면 객체로, 아니면 문자열 그대로 (로그 한 줄은 평문으로 온다) */
function parse(data: string) {
  if (!data) return null;
  try {
    return JSON.parse(data);
  } catch {
    return data;
  }
}

/** SSE 구독. 이벤트 이름별 처리기를 받는다. 연결이 끊기면 브라우저가 자동으로 다시 연결한다. */
export function useEventSource(url: string, handlers: Record<string, (data: any) => void>) {
  const ref = useRef(handlers);
  ref.current = handlers;
  useEffect(() => {
    const source = new EventSource(url);
    const names = Object.keys(ref.current);
    const listeners = names.map((name) => {
      const listener = (e: MessageEvent) => ref.current[name]?.(parse(e.data));
      source.addEventListener(name, listener as EventListener);
      return [name, listener] as const;
    });
    return () => {
      listeners.forEach(([name, listener]) => source.removeEventListener(name, listener as EventListener));
      source.close();
    };
  }, [url]);
}
