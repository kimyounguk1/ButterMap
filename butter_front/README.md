# Buttertteok Map MVP

기능 구현 중심으로 만든 바닐라 JS 프론트 MVP입니다.

## 포함 기능

- 네이버 지도 렌더링
- 판매처 마커 표시 및 클릭 상세
- 새 장소 등록 요청
- 선착순 쿠폰 요청 버튼
- 결제 테스트 버튼

## 빠른 시작

1. `index.html`의 네이버 지도 키를 교체합니다.
   - `YOUR_NAVER_MAP_CLIENT_ID`
2. `app.js`의 API 주소를 백엔드에 맞게 바꿉니다.
   - `API_BASE_URL`
3. 정적 서버로 실행합니다.
   - VS Code Live Server 또는 임의의 정적 파일 서버

## 백엔드 엔드포인트(예시)

- `GET /api/stores`
- `POST /api/stores/suggest`
- `POST /api/coupons/butter-first-come/claim`
- `POST /api/orders`
- `POST /api/orders/{orderId}/pay`

## Deep Dive 포인트

백엔드는 아래 문서를 참고해 `결제 멱등성 + Outbox 알림` 구조로 구현하세요.

- `docs/backend-design.md`
- `docs/schema.sql`
