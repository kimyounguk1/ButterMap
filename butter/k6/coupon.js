import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    // 200명이 동시에 접속하는 설정
    vus: 200,
    iterations: 200, // 총 200번의 요청이 완료되면 종료
};

export default function () {
    // 본인의 서버 주소와 포트로 수정하세요.
    // 도커 내부망을 쓰면 host.docker.internal을 사용해야 할 수도 있습니다.
    const url = 'http://host.docker.internal:8080/butter/coupons/butter-first-come/claim';

    // 유저 ID를 1부터 200까지 다르게 보내기 위해 k6의 고유 ID 활용
    const payload = JSON.stringify({
        userId: __VU,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
    };

    const res = http.post(url, payload, params);

    // 응답 결과 확인 (성공 시 200 혹은 201 등으로 체크)
    check(res, {
        'is status 200': (r) => r.status === 200,
        'is status 201': (r) => r.status === 201,
    });

    // 너무 동시에 몰리는 것을 방지하려면 아주 짧은 대기시간을 줄 수 있습니다.
    // sleep(0.1);
}