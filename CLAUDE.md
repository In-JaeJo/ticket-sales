# CLAUDE.md

> 선착순 티켓 판매에서 초과 판매 0건·메시지 유실 0건을 증명하는 백엔드 포트폴리오다 (Kotlin, Spring Boot, MySQL, Redis, Kafka).
> 설계는 [ARCHITECTURE.md](ARCHITECTURE.md)가 원본이다. 이 파일은 요약과 작업 방식만 담는다.
> 이 파일은 AI 코딩 도구가 작업 전에 읽는 지시서다. 사람을 위한 설명과 AI 활용 방식은 [README.md](README.md)에 있다.
> 현재 단계: 7단계까지 구현 완료. 남은 일: GitHub 첫 업로드 뒤 CI 통과 확인과 README 배지.

## 작업 전에 읽을 곳

| 작업 | ARCHITECTURE.md 섹션 |
|---|---|
| 인프라·Docker 설정 | 2. 인프라와 선택 이유 |
| 대기열·주문·결제·취소 | 3. 요청 흐름 |
| 재고 차감 | 4. 재고 차감 5가지 방식 |
| Kafka·Outbox·소비자·DLQ | 5. 메시지 신뢰성 |
| 리셋·장애 주입·가짜 결제·가상 손님·화면·k6 | 6. 데모와 측정 |
| 패키지·클래스 배치 | 7. 코드 구조 |
| 무엇이든 | 8. 규칙, 9. 완료 기준 테스트 |

## 저장소 구조

최상위 폴더는 따로 빌드·실행되는 단위로 나누고, 백엔드 안은 업무 단위 패키지로 나눈다(ARCHITECTURE.md 7장).

```
ticket-sales/
├── README.md · ARCHITECTURE.md · CLAUDE.md
├── docs/demo.gif             README 첫 화면의 시연 GIF
├── .github/workflows/ci.yml  CI (백엔드 테스트, 화면 빌드, Compose 설정 검사)
├── docker-compose.yml        전체 기동
├── demo.sh                   데모·측정 스크립트 (up, compare, open, crowd, failure)
├── backend/                  Spring Boot (Kotlin, Gradle)
│   ├── src/main/kotlin/com/ticketsales/
│   │   ├── queue/  product/  order/  payment/     도메인
│   │   └── common/  outbox/  demo/                인프라
│   └── src/main/resources/   설정, MySQL 스키마(schema.sql)
├── frontend/                 React 손님·관제 화면
├── infra/                    nginx, Prometheus, Grafana 설정
└── k6/                       부하 시나리오, SSE 확장 k6 이미지, 가상 손님 실행기, 측정 기록(results/*.md)
```

- 로컬 Docker는 OrbStack을 쓴다. Testcontainers는 별도 설정 없이 동작한다.
- 저장소가 `~/Desktop` 아래에 있으면 macOS가 OrbStack의 바탕화면 폴더 접근을 막아, 설정 파일을 연결(바인드 마운트)한 컨테이너(nginx, Prometheus, Grafana)가 파일을 읽다 멈춘다. 시스템 설정 → 개인정보 보호 및 보안 → 파일 및 폴더에서 OrbStack의 데스크탑 폴더 접근을 허용한다.

## 반드시 지킬 규칙 (자세한 이유는 ARCHITECTURE.md 8장)

- R1 의존은 `demo → 도메인 → outbox → common` 한 방향. 도메인(queue·product·order·payment)끼리 직접 의존 금지
- R2 도메인 핵심(엔티티·UseCase·Port)은 허용 목록만 의존. Web·Kafka·Redis·Jackson은 Adapter에만. 현재 시각은 `java.time.Clock` 주입
- R3 Adapter에 `@Transactional` 금지. 트랜잭션 구간은 별도 빈으로 (같은 클래스 내부 호출은 트랜잭션이 안 걸림)
- R4 Controller는 Repository 직접 사용 금지
- R5 도메인 경계를 넘는 계약·이벤트에 엔티티 금지
- R6 재고 복구는 이번 요청에서 차감에 성공한 경우에만
- R7 새 이벤트는 항상 Outbox 경유 (Kafka 직접 전송은 같은 메시지 ID 재전송인 운영 기능뿐)
- R8 멱등 키는 Outbox `message_id` (Kafka 헤더 `X-Message-Id`)
- R9 처리 실패 시 멱등 키 삭제 후 예외 재던짐. 앱이 죽어 남은 "처리 중" 키는 재시작 뒤 이어받아 다시 처리
- R10 보상은 되돌릴 자원을 가진 도메인이 처리
- R11 Redis·Kafka는 DB 롤백에 참여하지 않는다. 트랜잭션 밖에서 하고 명시적 복구
- R12 재고 차감 방식 전환은 리셋과 함께만
- R13 낙관적 락 재시도는 트랜잭션 바깥에서 새 트랜잭션으로
- R14 소비자 안의 외부 호출(PG)에는 주문 ID를 멱등 키로
- R15 상태 변경 이벤트는 상태가 실제로 바뀔 때만 발행. 상태를 바꿀 주문은 행을 잠그고 읽음
- R16 메시지는 지금 판 번호가 아니라 그 주문의 판 번호(`X-Run-Id`)를 실음. 공통 소비자가 지금 판이 아닌 메시지를 거름
- R17 주문 취소로 인한 재고 복구는 주문 ID 기준으로 한 번만

## 작업 방식

- 설계와 충돌하면 코드를 우회하지 말고 멈춘다. 문서의 어느 규칙과 부딪히는지 설명하고 사용자 확인을 받은 뒤 ARCHITECTURE.md부터 고친다.
- 완료는 테스트로 증명한다. ARCHITECTURE.md 9장의 해당 테스트(T번호)가 통과해야 끝난 것이다. 수정안도 먼저 테스트로 되는지 확인하고 진행한다.
- 파일·클래스 이름과 데이터 형식은 자유. 역할이 드러나게, 프로젝트 전체에서 겹치지 않게 짓는다(겹치면 빈 이름 충돌로 기동 실패). 형식은 코드(DTO·이벤트 클래스)가 원본이고 API 문서는 springdoc이 만든다.
- 파일 이름은 영어, 문서 내용과 설명은 한글.
- 범위 밖 작업(좌석 지정, 로그인, 결제 여러 종, 캐싱, Redis 이중화 등 — 10장)은 하지 않는다.

## 구현 순서

각 단계는 "끝났다는 기준"의 테스트가 통과해야 끝난다. 테스트 번호는 ARCHITECTURE.md 9장.

| 단계 | 할 일 | 끝났다는 기준 |
|---|---|---|
| 1. 골격 | `backend/` 생성, Compose로 MySQL·Redis·Kafka·Kafka UI 기동, 패키지 7개, ArchUnit 규칙 R1~R5와 위반 예제 | T15, T16, `docker compose up` 후 앱 정상 |
| 2. 대표 흐름 | Redis 방식 하나로 주문 → Outbox → Kafka → 가짜 결제 → 실패 → 주문 취소 → 재고 복구. 공통 소비자(판 번호, 멱등, 재시도·DLQ) 포함. 주문에 필요한 입장 토큰은 토큰 일괄 발급 API로 받는다(대기열은 3단계) | T5, T6, T7, T9, T11, T12, T14, T17, T20 (이후 보강: T21 동시 취소, T22 재고 복구 한 번만, T23 DLQ 재처리 때 남은 처리 중 표시, T24 재시도 때 남은 처리 중 표시) |
| 3. 나머지 백엔드 | 재고 차감 나머지 4가지, 대기열(줄 서기 케이스 포함) + SSE + 판매 상태(정각 오픈 포함), 시간 초과 취소, 데모 API(리셋, 방식, 장애, DLQ 재처리, 설정) | T1, T2, T3, T4, T8, T10, T13, T18, T19 |
| 4. 관측 | Prometheus 지표, Grafana 대시보드 자동 등록, 1만 연결용 Tomcat·nginx 설정 | Grafana에서 TPS·p95·컨슈머 지연·DLQ 수가 보임 |
| 5. 측정 | SSE 확장 k6 이미지, 비교·오픈 시나리오, `./demo.sh compare`·`open`·`crowd`·`failure` | README 결과표 두 개(재고 차감 5가지, 오픈 상황 1만 명)가 실측값으로 채워짐 |
| 6. 화면 | `frontend/`에 손님 화면 + 관제 화면 한 페이지, 관제 화면에서 가상 손님 투입(실행기 컨테이너), 정각 오픈 카운트다운 | 버튼 하나로 투입한 가상 손님 1만 명과 함께 정각에 줄 서서 구매하고, 장애 버튼을 눌러도 상단 지표가 그대로인 장면이 보임 |
| 7. 마무리 | README GIF, GitHub Actions 테스트 배지 | 처음 보는 사람이 README만으로 30초 안에 이해 |

- 2단계를 먼저 하는 이유: Redis·Kafka·트랜잭션·판 번호가 한 흐름에 다 걸려 설계가 맞는지 가장 빨리 드러난다. 설계와 다른 결과가 나오면 ARCHITECTURE.md부터 고친다.
- 화면(6단계)은 측정 뒤에 한다. 핵심 근거는 측정 결과이고, 화면은 그것을 보여주는 수단이다. 시간이 없으면 건너뛰어도 포트폴리오는 성립한다.
