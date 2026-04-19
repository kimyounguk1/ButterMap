const API_BASE_URL = "http://localhost:8080/butter";
const USE_MOCK_DATA = false;
const RANKING_REFRESH_MS = 10000;
const API_TIMEOUT_MS = 5000;

const defaultCenter = { lat: 37.5666805, lng: 126.9784147 };

let map;
let markers = [];
let infoWindow;

const storeDetailEl = document.getElementById("store-detail");
const logListEl = document.getElementById("log-list");
const rankingListEl = document.getElementById("ranking-list");
const registerDialog = document.getElementById("register-dialog");
const registerForm = document.getElementById("register-form");
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: API_TIMEOUT_MS
});

const mockStores = [
  { id: 1, name: "버터떡 홍대점", address: "서울 마포구", lat: 37.5563, lng: 126.9236, description: "쫀득한 버터떡" },
  { id: 2, name: "버터떡 강남점", address: "서울 강남구", lat: 37.4981, lng: 127.0276, description: "오후 2시 품절 주의" },
  { id: 3, name: "버터떡 잠실점", address: "서울 송파구", lat: 37.5133, lng: 127.1001, description: "쿠폰 사용 가능" }
];

const mockRanking = [
  { storeId: 2, storeName: "버터떡 강남점", score: 132 },
  { storeId: 1, storeName: "버터떡 홍대점", score: 121 },
  { storeId: 3, storeName: "버터떡 잠실점", score: 98 }
];

function log(message) {
  const li = document.createElement("li");
  li.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
  logListEl.prepend(li);
}

function renderStoreDetail(store) {
  storeDetailEl.innerHTML = `
    <strong>${store.name}</strong><br />
    주소: ${store.address || "-"}<br />
    좌표: ${store.lat}, ${store.lng}<br />
    설명: ${store.description || "-"}<br />
    재고: ${store.quantity || 0} <br />
    가격: ${store.price}
  `;
}

function clearMarkers() {
  markers.forEach((marker) => marker.setMap(null));
  markers = [];
}

function addMarker(store) {
  const marker = new naver.maps.Marker({
    position: new naver.maps.LatLng(store.lat, store.lng),
    map,
    icon: {
      content: `
        <div style="
          background-color: #FFD700; 
          border: 2px solid #fff; 
          border-radius: 50%; 
          width: 24px; 
          height: 24px; 
          display: flex; 
          align-items: center; 
          justify-content: center; 
          box-shadow: 0 2px 6px rgba(0,0,0,0.3);
          cursor: pointer;">
          <img src="./butter.png" style="width: 26px; height: 26px; object-fit: contain;" alt="butter">
          <div style="background: #FFF; width: 8px; height: 8px; border-radius: 50%;"></div>
        </div>
      `,
      anchor: new naver.maps.Point(12, 12), // 마커의 중심점을 중앙으로 잡음
    }
  });

  naver.maps.Event.addListener(marker, "click", () => {
    renderStoreDetail(store);

    const html = `
      <div style="padding:8px;min-width:180px;">
        <strong>${store.name}</strong><br />
        <span style="font-size:12px;">${store.address || ""}</span>
        <span style="font-size:12px;">${store.quantity || 0}</span>
      </div>
    `;

    infoWindow.setContent(html);
    infoWindow.open(map, marker);
  });

  markers.push(marker);
}

async function request(path, options = {}) {
  try {
    const response = await apiClient.request({
      url: path,
      method: options.method || "GET",
      data: options.data,
      params: options.params,
      headers: options.headers || {}
    });
    return response.data ?? null;
  } catch (error) {
    if (axios.isAxiosError(error)) {
      const serverMessage = error.response?.data;
      if (typeof serverMessage === "string" && serverMessage.trim()) {
        throw new Error(serverMessage);
      }
      if (error.response) {
        throw new Error(`HTTP ${error.response.status}`);
      }
      if (error.code === "ECONNABORTED") {
        throw new Error("요청 시간이 초과되었습니다.");
      }
      throw new Error("서버에 연결할 수 없습니다.");
    }
    throw error;
  }
}

function connectRankingStream() {
    // 1. SSE 연결 (백엔드 /butter/stream 으로)
    const eventSource = new EventSource("http://localhost:8080/butter/stream?limit=10");

    // 2. 백엔드에서 .name("ranking") 으로 보낸 이벤트 수신
    eventSource.addEventListener("ranking", (event) => {
        const ranking = JSON.parse(event.data);
        renderRanking(ranking);
        log("랭킹 업데이트 수신");
    });

    // 3. 연결 끊기면 3초 후 재연결
    eventSource.onerror = () => {
        log("랭킹 스트림 연결 끊김, 재연결 시도...");
        eventSource.close();
        setTimeout(connectRankingStream, 3000);
    };
}

async function loadStores() {
  try {
    const stores = USE_MOCK_DATA ? mockStores : await request("/stores");
    clearMarkers();
    stores.forEach(addMarker);
    log(`판매처 ${stores.length}개를 불러왔습니다.`);
  } catch (error) {
    log(`판매처 조회 실패: ${error.message}`);
  }
}

async function submitStoreSuggestion(payload) {
  try {
    if (USE_MOCK_DATA) {
      mockStores.push({ ...payload, id: Date.now() });
    } else {
      await request("/stores/suggest", {
        method: "POST",
        data: payload
      });
    }
    log("새 장소 등록 요청이 완료되었습니다.");
    await loadStores();
  } catch (error) {
    log(`장소 등록 실패: ${error.message}`);
  }
}

async function claimCoupon() {
  try {
    const result = USE_MOCK_DATA
      ? { code: "MOCK-COUPON-001" }
      : await request("/coupons/butter-first-come/claim", {
        method: "POST",
        data: { userId: "1" }
      });
    log(`쿠폰 발급 성공: ${result.code || "CODE-UNKNOWN"}`);
  } catch (error) {
    log(`쿠폰 발급 실패: ${error.message}`);
  }
}

async function testPayment() {
  try {
    const order = USE_MOCK_DATA
      ? { orderId: `MOCK-ORDER-${Date.now()}` }
      : await request("/orders", {
        method: "POST",
        data: {
          userId: "guest-user",
          amount: 3000,
          itemName: "버터떡 세트"
        }
      });

    log(`주문 생성 완료: orderId=${order.orderId}`);

    const payment = USE_MOCK_DATA
      ? { paymentId: `MOCK-PAY-${Date.now()}` }
      : await request(`/orders/${order.orderId}/pay`, {
        method: "POST",
        data: {
          paymentMethod: "CARD",
          idempotencyKey: crypto.randomUUID()
        }
      });

    log(`결제 요청 완료: paymentId=${payment.paymentId}`);
  } catch (error) {
    log(`결제 테스트 실패: ${error.message}`);
  }
}

function renderRanking(items) {
  rankingListEl.innerHTML = "";

  if (!items.length) {
    const li = document.createElement("li");
    li.textContent = "아직 랭킹 데이터가 없습니다.";
    rankingListEl.appendChild(li);
    return;
  }

  items.slice(0, 10).forEach((item, idx) => {
    const li = document.createElement("li");
    li.textContent = `${idx + 1}. ${item.storeName} (${item.score}점)`;
    rankingListEl.appendChild(li);
  });
}

async function loadRanking() {
  try {
    const ranking = USE_MOCK_DATA
      ? mockRanking
      : await request("/rankings/stores?period=daily&limit=10");
    renderRanking(ranking);
  } catch (error) {
    log(`랭킹 조회 실패: ${error.message}`);
  }
}

function bindEvents() {
  document.getElementById("btn-load-stores").addEventListener("click", loadStores);
  document.getElementById("btn-open-register").addEventListener("click", () => registerDialog.showModal());
  document.getElementById("btn-cancel-register").addEventListener("click", () => registerDialog.close());
  document.getElementById("btn-claim-coupon").addEventListener("click", claimCoupon);
  // document.getElementById("btn-test-payment").addEventListener("click", testPayment);

  registerForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const formData = new FormData(registerForm);
    const payload = {
      name: formData.get("name"),
      address: formData.get("address"),
      lat: Number(formData.get("lat")),
      lng: Number(formData.get("lng")),
      description: formData.get("description")
    };
    registerDialog.close();
    registerForm.reset();
    await submitStoreSuggestion(payload);
  });
}

function initMap() {
  map = new naver.maps.Map("map", {
    center: new naver.maps.LatLng(defaultCenter.lat, defaultCenter.lng),
    zoom: 13
  });

  infoWindow = new naver.maps.InfoWindow();

  naver.maps.Event.addListener(map, "click", (e) => {
    const latInput = registerForm.querySelector('input[name="lat"]');
    const lngInput = registerForm.querySelector('input[name="lng"]');
    latInput.value = e.coord.y.toFixed(7);
    lngInput.value = e.coord.x.toFixed(7);
    log("지도 클릭 좌표를 등록 폼에 채웠습니다.");
  });
}

function bootstrap() {
  if (!window.naver || !window.naver.maps) {
    log("네이버 지도 SDK 로드 실패: ncpKeyId를 확인하세요.");
    return;
  }
  if (USE_MOCK_DATA) {
    log("mock 모드로 실행 중입니다. API 없이 데모 가능합니다.");
  }
  initMap();
  bindEvents();
  loadStores();
  // loadRanking();
  // setInterval(loadRanking, RANKING_REFRESH_MS);
  connectRankingStream();
}

bootstrap();
