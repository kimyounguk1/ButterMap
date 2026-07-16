package com.example.butter.domain.config.redis;

import com.example.butter.domain.service.CouponWorker;
import com.example.butter.domain.service.RankingUpdateListener;
import io.lettuce.core.ClientOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import io.lettuce.core.SocketOptions;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import java.time.Duration;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisConfig {
    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Bean
    public RedisScript<Long> couponScript() {
        return RedisScript.of(new ClassPathResource("coupon.lua"), Long.class);
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 1. 소켓 타임아웃 (연결 자체의 타임아웃)
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(100))
                .build();

        // 2. 클라이언트 옵션 (소켓 옵션 적용)
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .build();

        // 3. Lettuce 전용 클라이언트 설정 (명령어 실행 타임아웃 포함)
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(150)) // Redis 명령 실행 시간 제한
                .clientOptions(clientOptions)
                .build();

        // 4. Redis 호스트와 포트 설정 (기본값 localhost, 6379)
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration("localhost", 6379);

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter couponListenerAdapter,   // 쿠폰용 어댑터
            MessageListenerAdapter rankingListenerAdapter) { // 랭킹용 어댑터

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 1. 쿠폰 알림 채널 등록
        container.addMessageListener(couponListenerAdapter, new ChannelTopic("coupon:alarm"));

        // 2. 랭킹 변경 알림 채널 등록
        container.addMessageListener(rankingListenerAdapter, new ChannelTopic("ranking:change:events"));

        return container;
    }

    @Bean
    public MessageListenerAdapter couponListenerAdapter(CouponWorker couponWorker) {
        return new MessageListenerAdapter(couponWorker);
    }

    @Bean
    public MessageListenerAdapter rankingListenerAdapter(RankingUpdateListener rankingUpdateListener) {
        return new MessageListenerAdapter(rankingUpdateListener);
    }

}
