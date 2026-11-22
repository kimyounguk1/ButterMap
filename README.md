# ButterMap

버터떡 매장 정보를 조회하고, 주문 기반 랭킹과 선착순 쿠폰 발급을 실험한 개인 프로젝트입니다.  
단순 CRUD보다 Redis 캐시, 로컬 캐시, Lua Script, SSE, k6 부하 테스트를 직접 적용해 읽기 성능과 동시성 처리 흐름을 검증하는 데 초점을 두었습니다.

## 핵심 목표

- 매장 목록 조회에서 DB 접근을 줄이기 위한 다층 캐시 구조 설계
- 선착순 쿠폰 발급에서 중복 참여와 초과 발급을 Redis Lua Script로 제어
- Redis Stream Consumer Group과 DB unique 제약으로 쿠폰 DB 저장을 유저당 정확히 한 번으로 보장
- 주문 발생 시 Redis Sorted Set 기반 실시간 매장 랭킹 갱신
- SSE(Server-Sent Events)를 이용한 랭킹 변경 실시간 전달
- k6를 이용한 고부하 요청 시나리오 작성

## 기술 스택

| 영역 | 기술 |
|---|---|
| Backend | Java 17, Spring Boot 3.5, Spring Web, Spring Data JPA |
| Database | MySQL |
| Cache / Queue | Redis (Set, Hash, Sorted Set, Stream), Spring Cache, Caffeine |
| Realtime | SSE, Redis Pub/Sub |
| Monitoring | Spring Boot Actuator, Micrometer, Prometheus |
| Test | JUnit 5, k6 |
| Frontend | Vite, Axios |

## 주요 기능

### 매장 조회 캐시

매장 목록 조회는 DB를 바로 조회하지 않고 다음 순서로 처리합니다.

```text
Local Cache(Caffeine)
  -> Redis List + Redis Hash
  -> MySQL
```

- `storeList` 로컬 캐시에 매장 목록을 저장해 짧은 시간 동안 반복 조회 비용을 줄였습니다.
- Redis에는 `store:ids` 목록과 `store:{id}` Hash를 분리 저장했습니다.
- Redis Hash는 pipeline으로 한 번에 조회해 N번 round-trip을 줄였습니다.
- 캐시 일부가 누락되면 DB에서 전체 데이터를 다시 읽어 Redis와 local cache를 재동기화합니다.

### 선착순 쿠폰 발급

당첨 판정은 Redis Lua Script로, DB 저장은 Redis Stream Consumer Group으로 처리합니다.

```text
쿠폰 요청
  -> Lua Script (원자적 실행)
       SISMEMBER coupon:users  : 중복 참여 검사
       SCARD     coupon:users  : 수량 초과 검사
       SADD      coupon:users  : 당첨자 등록
       XADD      coupon:stream : DB 저장 작업 적재
  -> CouponWorker가 XREADGROUP으로 읽어 DB 저장
  -> 저장 성공 시에만 XACK
```

- 중복 참여 방지, 최대 발급 수량 제한
- 쿠폰 발급 요청과 DB 저장 분리로 순간 트래픽의 DB write 부하 완화
- 당첨자 등록과 Stream 적재를 한 스크립트에서 실행해 "당첨됐는데 저장 작업이 없는" 상태 방지

#### DB 저장을 정확히 한 번으로 보장하기

초기 구현은 `LPUSH` 큐 + Pub/Sub 알림 + `RPOP` 소비 방식이었는데, 두 가지 문제가 있었습니다.

- **유실**: `RPOP`으로 꺼내는 순간 큐에서 사라지므로, DB 저장 실패나 서버 장애 시 당첨자의 쿠폰이 영구히 누락됨. Pub/Sub 알림도 구독자가 없으면 사라짐
- **중복**: `coupons.user_id`에 unique 제약이 없어, 재시도를 넣으면 같은 유저에게 중복 저장될 수 있음

이를 두 가지로 나눠 해결했습니다.

| 보장 | 방법 |
|---|---|
| 유실 방지 (at-least-once) | Redis Stream Consumer Group. 읽은 메시지는 `XACK` 전까지 PEL에 남고, DB 저장 성공 시에만 ACK |
| 중복 방지 (at-most-once) | `coupons.user_id` unique 제약 + 저장 전 `existsByUserId` 확인 |

장애 상황별 동작:

| 상황 | 결과 |
|---|---|
| 정상 처리 | DB 저장 → `XACK` |
| DB 저장 실패 / 워커 다운 | ACK 안 됨 → PEL에 남음 → 스케줄러가 30초 이상 멈춘 메시지를 `XCLAIM`으로 회수해 재처리 |
| 저장 후 `XACK` 직전 장애 | 재전달됨 → 이미 발급된 유저로 판단해 저장 없이 ACK |
| 5회 이상 실패 | `coupon:stream:dlq`로 이동 후 ACK (수동 확인 필요) |

남은 한계: Redis 서버 자체의 데이터 유실은 Stream으로 막을 수 없으므로 AOF 등 영속화 설정이 필요합니다.

### 주문 기반 랭킹

주문이 생성되면 Redis Sorted Set의 점수를 증가시켜 매장 랭킹을 갱신합니다.

```text
POST /butter/orders
  -> 주문 저장
  -> store:ranking:daily score 증가
  -> ranking:change:events publish
  -> SSE로 랭킹 브로드캐스트
```

랭킹 조회는 Redis ZSet에서 상위 N개 매장을 읽고, 매장명은 DB에서 보강합니다.

### 실시간 랭킹 SSE

클라이언트는 `/butter/stream`으로 SSE 연결을 맺고, 랭킹 변경 이벤트를 받습니다.

- 연결 직후 현재 랭킹을 즉시 전송
- Redis Pub/Sub 이벤트 발생 시 전체 SSE client에 브로드캐스트 (랭킹은 알림 하나를 놓쳐도 다음 갱신 때 최신화되므로 Pub/Sub 사용)
- 연결 종료, timeout, error 발생 시 emitter 정리

## API 요약

| Method | Path | 설명 |
|---|---|---|
| GET | `/butter/stores` | 캐시 기반 매장 목록 조회 |
| GET | `/butter/storesdb` | DB 직접 매장 목록 조회 |
| GET | `/butter/store/{id}` | 매장 단건 조회 |
| POST | `/butter/stores/suggest` | 매장 제안 등록 |
| POST | `/butter/coupons/butter-first-come/claim` | 선착순 쿠폰 발급 요청 |
| GET | `/butter/stream` | 실시간 랭킹 SSE 구독 |
| POST | `/butter/orders` | 주문 생성 및 랭킹 갱신 |
| GET | `/butter/local` | 로컬 캐시 조회 테스트 |
| POST | `/butter/local` | 로컬 캐시 저장 테스트 |

## 프로젝트 구조

```text
butter_map
├── butter                 # Spring Boot backend
│   ├── src/main/java
│   │   └── com/example/butter/domain
│   │       ├── config     # Redis, Cache, Web 설정
│   │       ├── controller # REST / SSE API
│   │       ├── dto
│   │       ├── entity
│   │       ├── repository
│   │       └── service    # 캐시, 쿠폰, 주문, 랭킹 로직
│   └── src/main/resources
│       └── coupon.lua     # 선착순 쿠폰 Lua Script (판정 + Stream 적재)
├── butter_front           # Vite frontend
└── k6                     # 부하 테스트 스크립트
```

## 실행 방법

### Backend

```bash
cd butter
./gradlew bootRun
```

### Frontend

```bash
cd butter_front
npm install
npm run dev
```

### k6 부하 테스트

```bash
k6 run k6/test.js
```

현재 k6 스크립트는 `/butter/storesdb`에 순간 요청을 몰아 DB 직접 조회 경로를 확인하는 용도입니다.

## 정리

ButterMap은 작은 도메인 안에서 캐시와 이벤트 기반 처리의 차이를 직접 비교하기 위한 프로젝트입니다.  
특히 매장 목록 조회, 선착순 쿠폰, 실시간 랭킹처럼 트래픽이 몰리기 쉬운 기능을 Redis 중심으로 분리해 보면서 읽기 성능과 동시성 제어를 실험했습니다.
