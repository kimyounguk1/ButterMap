package com.example.butter.domain.service;

import com.example.butter.domain.entity.Coupon;
import com.example.butter.domain.entity.User;
import com.example.butter.domain.repository.CouponRepository;
import com.example.butter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CouponWorker implements MessageListener {

    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        // "알림"이 오면 큐가 빌 때까지 데이터를 꺼내 DB에 저장합니다.
        while (true) {
            String userIdStr = redisTemplate.opsForList().rightPop("coupon:queue");

            if (userIdStr == null) break; // 더 이상 데이터가 없으면 중단

            try {
                Long userId = Long.parseLong(userIdStr);
                User user =  new User();
                user.setId(userId);

                couponRepository.save(new Coupon(user));

                System.out.println("실시간 처리 완료 - 유저ID: " + userId);
            } catch (Exception e) {
                System.err.println("DB 저장 에러: " + userIdStr);
            }
        }
    }
}