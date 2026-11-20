import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 로컬 개발: npm run dev → /api는 docker compose의 nginx(80)로 보낸다
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost' } },
});
