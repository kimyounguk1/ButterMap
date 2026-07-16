import http from 'k6/http';

export const options = {
    scenarios: {
        burst_test: {
            executor: 'constant-arrival-rate',
            rate: 5000, // 초당 5000 요청
            timeUnit: '1s',
            duration: '1s',
            preAllocatedVUs: 500,
            maxVUs: 1000,
        },
    },
};

export default function () {
    http.get('http://host.docker.internal:8080/butter/storesdb');
}