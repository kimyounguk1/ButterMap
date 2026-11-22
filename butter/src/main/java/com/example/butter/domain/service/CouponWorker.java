package com.example.butter.domain.service;

import com.example.butter.domain.entity.Coupon;
import com.example.butter.domain.entity.User;
import com.example.butter.domain.repository.CouponRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 쿠폰 발급 Stream 소비자.
 *
 * 전달 보장: at-least-once (Redis Stream Consumer Group)
 *  - XREADGROUP 으로 읽은 메시지는 XACK 전까지 PEL(Pending Entries List)에 남는다.
 *  - DB 저장에 성공했을 때만 XACK 하므로, 저장 실패/워커 장애 시 메시지가 유실되지 않는다.
 *  - 처리되지 못하고 오래 남은 메시지는 {@link #recoverPending()} 이 XCLAIM 으로 회수해 재처리한다.
 *
 * 중복 방지: 멱등 처리 (DB unique 제약 uk_coupons_user_id)
 *  - "DB 커밋 → XACK 전에 장애" 이면 같은 메시지가 다시 전달된다.
 *  - 이미 발급된 유저면 저장 없이 ACK 만 하므로, 몇 번 재전달되어도 쿠폰은 1장이다.
 *
 * 두 가지를 합쳐 유저당 쿠폰 발급을 "정확히 한 번"으로 만든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponWorker implements StreamListener<String, MapRecord<String, String, String>> {

    public static final String USERS_KEY = "coupon:users";
    public static final String STREAM_KEY = "coupon:stream";
    public static final String GROUP = "coupon-group";
    public static final String DLQ_KEY = "coupon:stream:dlq";

    /** 이 시간 이상 ACK 되지 않은 메시지는 처리 중이던 워커가 죽은 것으로 보고 회수한다. */
    private static final Duration MIN_IDLE = Duration.ofSeconds(30);
    /** 이 횟수 이상 전달됐는데도 실패하면 재시도로 해결될 문제가 아니라고 보고 DLQ 로 보낸다. */
    private static final long MAX_DELIVERY_COUNT = 5;
    private static final long RECOVER_BATCH_SIZE = 100;

    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;

    /** 인스턴스마다 다른 컨슈머 이름. 여러 서버가 같은 그룹에서 메시지를 나눠 가진다. */
    @Getter
    private final String consumerName = "coupon-worker-" + UUID.randomUUID();

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        process(message.getId(), message.getValue().get("userId"));
    }

    /**
     * 장애 등으로 ACK 되지 못한 메시지 회수.
     * XCLAIM 에 minIdle 조건을 걸기 때문에 여러 인스턴스가 동시에 돌아도 한 곳만 가져간다.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void recoverPending() {
        PendingMessages pendingMessages;
        try {
            pendingMessages = redisTemplate.opsForStream()
                    .pending(STREAM_KEY, GROUP, Range.unbounded(), RECOVER_BATCH_SIZE);
        } catch (Exception e) {
            log.warn("Pending 메시지 조회 실패", e);
            return;
        }

        for (PendingMessage pending : pendingMessages) {
            if (pending.getElapsedTimeSinceLastDelivery().compareTo(MIN_IDLE) < 0) {
                continue;
            }

            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                    .claim(STREAM_KEY, GROUP, consumerName, MIN_IDLE, pending.getId());

            for (MapRecord<String, Object, Object> record : claimed) {
                String userId = String.valueOf(record.getValue().get("userId"));
                if (pending.getTotalDeliveryCount() >= MAX_DELIVERY_COUNT) {
                    moveToDeadLetter(record.getId(), userId);
                } else {
                    log.info("Pending 메시지 재처리 - id: {}, userId: {}, 전달 횟수: {}",
                            record.getId(), userId, pending.getTotalDeliveryCount());
                    process(record.getId(), userId);
                }
            }
        }
    }

    private void process(RecordId recordId, String userIdStr) {
        try {
            Long userId = Long.parseLong(userIdStr);
            issueOnce(userId);
            ack(recordId);
            log.info("쿠폰 DB 저장 완료 - userId: {}", userId);
        } catch (NumberFormatException e) {
            // 재시도해도 성공할 수 없는 메시지
            log.error("잘못된 쿠폰 메시지 - id: {}, userId: {}", recordId, userIdStr);
            moveToDeadLetter(recordId, userIdStr);
        } catch (Exception e) {
            // ACK 하지 않음 → PEL 에 남아 recoverPending() 에서 재처리
            log.warn("쿠폰 DB 저장 실패, 재시도 대기 - id: {}, userId: {}", recordId, userIdStr, e);
        }
    }

    /** 같은 유저에 대해 몇 번 호출되어도 쿠폰은 1장만 남는다. */
    private void issueOnce(Long userId) {
        if (couponRepository.existsByUserId(userId)) {
            log.info("이미 발급된 유저 (재전달 메시지) - userId: {}", userId);
            return;
        }

        try {
            User user = new User();
            user.setId(userId);
            couponRepository.save(new Coupon(user));
        } catch (DataIntegrityViolationException e) {
            // 동시에 다른 워커가 먼저 저장한 경우 → unique 제약 위반이므로 이미 발급된 것
            // 그 외(존재하지 않는 유저 FK 위반 등)는 실제 실패이므로 다시 던진다.
            if (!couponRepository.existsByUserId(userId)) {
                throw e;
            }
        }
    }

    private void ack(RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, recordId);
    }

    private void moveToDeadLetter(RecordId recordId, String userIdStr) {
        try {
            redisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(DLQ_KEY)
                    .ofMap(Map.of(
                            "userId", String.valueOf(userIdStr),
                            "originId", recordId.getValue()
                    )));
            ack(recordId);
            log.error("쿠폰 발급 실패 메시지를 DLQ 로 이동 - id: {}, userId: {}", recordId, userIdStr);
        } catch (Exception e) {
            log.error("DLQ 이동 실패 - id: {}, userId: {}", recordId, userIdStr, e);
        }
    }
}
