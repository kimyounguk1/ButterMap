package com.example.butter.domain.config.redis;

import com.example.butter.domain.service.CouponWorker;
import com.example.butter.domain.service.RankingUpdateListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
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
