# 기본 k6는 SSE를 받을 수 없다. 대기열 입장 신호를 SSE로 받으려고 xk6-sse 확장을 넣어 빌드한다.
# xk6-sse v0.2.0은 k6 v2.0.0을 기준으로 만들어졌다. k6 1.x와 섞으면 빌드가 깨지고, 2.4.0에서는 SSE 클라이언트의 메서드가 보이지 않는다.
FROM grafana/xk6:1.5.1 AS build
RUN xk6 build v2.0.0 --with github.com/phymbert/xk6-sse@v0.2.0 --output /tmp/k6

FROM grafana/k6:2.0.0
COPY --from=build /tmp/k6 /usr/bin/k6
