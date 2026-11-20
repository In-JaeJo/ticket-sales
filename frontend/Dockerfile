FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# nginx가 화면을 서빙하고 /api는 앱으로 보낸다 (설정은 infra/nginx/nginx.conf)
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
