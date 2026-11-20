import { useState } from 'react';
import Admin from './Admin';
import Customer from './Customer';

export default function App() {
  // 장애 실험 버튼은 실제 서비스에는 없는 조작이라 평소에는 숨기고, 켰을 때만 처리 흐름에 나타난다
  const [lab, setLab] = useState(false);
  return (
    <div className="page">
      <header className="top">
        <div className="brand">
          <span className="logo">ticket-sales</span>
          <span className="sub">선착순 티켓 판매 · 초과 판매 0 · 메시지 유실 0</span>
        </div>
        <nav className="links">
          <button className={`btn lab-toggle ${lab ? 'on' : ''}`} onClick={() => setLab(!lab)}>
            {lab ? '장애 실험 끄기' : '장애 실험 켜기'}
          </button>
          <a href="http://localhost:3000" target="_blank" rel="noreferrer">Grafana</a>
          <a href="http://localhost:8081" target="_blank" rel="noreferrer">Kafka UI</a>
          <a href="/swagger-ui" target="_blank" rel="noreferrer">API 문서</a>
        </nav>
      </header>
      <main className="layout">
        <Customer />
        <Admin lab={lab} />
      </main>
    </div>
  );
}
