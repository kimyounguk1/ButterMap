package com.example.butter.domain.service;

import com.example.butter.domain.dto.AddStoreReqDto;
import com.example.butter.domain.dto.CouponReqDto;
import com.example.butter.domain.dto.OrderRequestDto;
import com.example.butter.domain.dto.StoreResDto;
import com.example.butter.domain.entity.Order;
import com.example.butter.domain.entity.Store;
import com.example.butter.domain.entity.User;
import com.example.butter.domain.repository.CouponRepository;
import com.example.butter.domain.repository.OrderRepository;
import com.example.butter.domain.repository.StoreRepository;
import com.example.butter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogicService {

    private final StoreRepository storeRepository;
    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> couponScript;
    private final RankingService rankingService;

    public List<StoreResDto> getStore() {
        List<Store> all = storeRepository.findAll();
        return all.stream().map(StoreResDto::new).toList();
    }

    public void suggestStore(AddStoreReqDto addStoreReqDto) {

        Store store = new Store(addStoreReqDto);

        storeRepository.save(store);

    }

    public void getCoupon(CouponReqDto couponReqDto) {
        String key = "coupon:users";
        String limit = "100"; // 선착순 인원 설정

        // 루아 스크립트 실행
        Long result = redisTemplate.execute(
                couponScript,
                Collections.singletonList(key), // KEYS[1]
                limit,                         // ARGV[1]
                String.valueOf(couponReqDto.getUserId()) // ARGV[2]
        );

        if (result == -1) {
            throw new RuntimeException("이미 참여하셨습니다.");
        } else if (result == 0) {
            throw new RuntimeException("모든 쿠폰이 소진되었습니다.");
        }

        // result == 1 이면 성공 (이미 루아 스크립트 내부에서 큐에 들어감)
        System.out.println("발급 대기열 등록 성공: " + couponReqDto.getUserId());

    }

    public void createOrder(OrderRequestDto dto) {
        // 1. 매장과 유저가 존재하는지 확인
        Store store = storeRepository.findById(dto.getStoreId())
                .orElseThrow(() -> new RuntimeException("매장을 찾을 수 없습니다."));
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new RuntimeException("유저를 찾을 수 없습니다."));

        // 2. 주문 엔티티 생성 및 저장
        Order order = new Order(store, user);
        orderRepository.save(order);

        // 3. 랭킹 업데이트 호출! (Redis 점수 1점 상승)
        rankingService.incrementOrderCount(store.getId());
    }
}
