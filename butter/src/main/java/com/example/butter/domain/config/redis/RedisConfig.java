package com.example.butter.domain.config.redis;

import com.example.butter.domain.service.CouponWorker;
import com.example.butter.domain.service.RankingUpdateListener;
import io.lettuce.core.ClientOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
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

@Slf4j
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
    @Primary
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
            MessageListenerAdapter rankingListenerAdapter) { // 랭킹용 어댑터

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 랭킹 변경 알림 채널 등록
        // (쿠폰은 Pub/Sub 대신 Redis Stream 으로 처리 → couponStreamContainer 참고)
        container.addMessageListener(rankingListenerAdapter, new ChannelTopic("ranking:change:events"));

        return container;
    }

    /**
     * Stream 소비 전용 커넥션 팩토리.
     * XREADGROUP BLOCK 은 pollTimeout 동안 응답을 기다리는데, 기본 팩토리의 commandTimeout(150ms)보다 길면
     * 매번 타임아웃 예외가 나므로 넉넉한 타임아웃을 가진 커넥션을 따로 사용한다.
     */
    @Bean
    public LettuceConnectionFactory couponStreamConnectionFactory() {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(5))
                .build();
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), clientConfig);
    }

    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> couponStreamContainer(
            @Qualifier("couponStreamConnectionFactory") RedisConnectionFactory streamConnectionFactory,
            StringRedisTemplate stringRedisTemplate,
            CouponWorker couponWorker) {

        createConsumerGroupIfAbsent(stringRedisTemplate);

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(1)) // XREADGROUP BLOCK 시간
                        .batchSize(10)
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(streamConnectionFactory, options);

        container.register(
                StreamReadRequest.builder(StreamOffset.create(CouponWorker.STREAM_KEY, ReadOffset.lastConsumed()))
                        .consumer(Consumer.from(CouponWorker.GROUP, couponWorker.getConsumerName()))
                        .autoAcknowledge(false)       // DB 저장 성공 후 워커가 직접 XACK
                        .cancelOnError(e -> false)    // 일시적인 Redis 오류로 구독이 끊기지 않도록
                        .errorHandler(e -> log.warn("쿠폰 Stream 읽기 오류", e))
                        .build(),
                couponWorker);

        container.start();
        return container;
    }

    /** XGROUP CREATE coupon:stream coupon-group 0 MKSTREAM (이미 있으면 무시) */
    private void createConsumerGroupIfAbsent(StringRedisTemplate redisTemplate) {
        try {
            redisTemplate.execute((RedisCallback<String>) connection -> connection.streamCommands().xGroupCreate(
                    CouponWorker.STREAM_KEY.getBytes(), CouponWorker.GROUP, ReadOffset.from("0"), true));
        } catch (Exception e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
    }

    private boolean isBusyGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    @Bean
    public MessageListenerAdapter rankingListenerAdapter(RankingUpdateListener rankingUpdateListener) {
        return new MessageListenerAdapter(rankingUpdateListener);
    }

}
