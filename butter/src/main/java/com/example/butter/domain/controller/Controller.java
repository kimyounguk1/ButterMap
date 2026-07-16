package com.example.butter.domain.controller;

import com.example.butter.domain.dto.*;
import com.example.butter.domain.service.LogicService;
import com.example.butter.domain.service.RankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequiredArgsConstructor
@RequestMapping("/butter")
public class Controller {

    private final LogicService service;
    private final RankingService rankingService;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @GetMapping("/stores")
    public ResponseEntity<?> getStore() {
        List<StoreResDto> store = service.getStoreList();
        return ResponseEntity.ok().body(store);
    }

    @GetMapping("/storesdb")
    public ResponseEntity<?> getStoreDB() {
        List<StoreResDto> store = service.getStore();
        return ResponseEntity.ok().body(store);
    }

    @GetMapping("/store/{id}")
    public OneStoreResDto getOneStore(@PathVariable Long id) {
        return service.getOneStore(id);
    }

    @PostMapping("/stores/suggest")
    public ResponseEntity<?> suggestStore(@RequestBody AddStoreReqDto addStoreReqDto) {
        service.suggestStore(addStoreReqDto);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/coupons/butter-first-come/claim")
    public ResponseEntity<?> getCoupon(@RequestBody CouponReqDto couponReqDto) {
        service.getCoupon(couponReqDto);

        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRankings(@RequestParam(defaultValue = "10") int limit) {
        // 1. 새로운 연결 생성 (타임아웃 30분)
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        this.emitters.add(emitter);

        // 2. 연결 종료/에러 시 리스트에서 제거
        // 정상종료, 시간초과, 에러발생
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError((e) -> emitters.remove(emitter));

        // 3. 연결 직후 현재 랭킹을 바로 한 번 보내줌 (첫 화면 로딩용)
        broadcastToOne(emitter, limit);

        return emitter;
    }

    // 랭킹 업데이트 알림이 오면 호출될 브로드캐스트 메서드
    public void broadcastAll() {
        List<StoreRankingDto> top10 = rankingService.getTopRankings(10);

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("ranking") // 프론트의 .addEventListener("ranking")과 일치
                        .data(top10));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    private void broadcastToOne(SseEmitter emitter, int limit) {
        try {
            emitter.send(SseEmitter.event()
                    .name("ranking")
                    .data(rankingService.getTopRankings(limit)));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
    }

    @PostMapping("/orders")
    public ResponseEntity<String> placeOrder(@RequestBody OrderRequestDto dto) {
        service.createOrder(dto);
        return ResponseEntity.ok("주문이 완료되었습니다.");
    }

    @GetMapping("/local")
    public String getLocalCache (@RequestParam String q) {
        return service.getLocalCache(q);

    }

    @PostMapping("/local")
    public String saveLocalCache(@RequestParam String q) {
        return service.saveLocalCache(q);
    }

}
