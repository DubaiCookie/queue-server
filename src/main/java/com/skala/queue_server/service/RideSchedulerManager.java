package com.skala.queue_server.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
@RequiredArgsConstructor
public class RideSchedulerManager {

    private static final Logger logger = LoggerFactory.getLogger(RideSchedulerManager.class);

    private static final String META_KEY_PREFIX = "ride:meta:";
    private static final String QUEUE_KEY_PREFIX = "queue:ride:";
    private static final String PREMIUM_SUFFIX = ":PREMIUM";
    private static final String GENERAL_SUFFIX = ":GENERAL";
    private static final String QUEUE_EVENT_TOPIC = "queue-event-topic";

    private final ThreadPoolTaskScheduler taskScheduler;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;

    // ride별 스케줄 저장
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> schedulerMap = new ConcurrentHashMap<>();

    public void registerRideScheduler(Long rideId, int ridingTimeSeconds) {

        ScheduledFuture<?> future =
                taskScheduler.scheduleAtFixedRate(
                        () -> dispatchRide(rideId),
                        Duration.ofSeconds(ridingTimeSeconds)
                );

        schedulerMap.put(rideId, future);

        logger.info("스케줄러 시작 - ride={} interval={}초", rideId, ridingTimeSeconds);
    }

    private void dispatchRide(Long rideId) {

        logger.debug("dispatch 시작 - ride={}", rideId);

        String metaKey = META_KEY_PREFIX + rideId;

        Object premiumObj = redisTemplate.opsForHash().get(metaKey, "capacityPremium");
        Object generalObj = redisTemplate.opsForHash().get(metaKey, "capacityGeneral");

        if (premiumObj == null || generalObj == null) {
            logger.warn("메타 없음 - ride={}", rideId);
            return;
        }

        int premiumQuota = Integer.parseInt(premiumObj.toString());
        int generalQuota = Integer.parseInt(generalObj.toString());

        String premiumKey = QUEUE_KEY_PREFIX + rideId + PREMIUM_SUFFIX;
        String generalKey = QUEUE_KEY_PREFIX + rideId + GENERAL_SUFFIX;

        // 1️⃣ READY / ALMOST_READY 이벤트 전송
        sendReadinessNotices(rideId, premiumKey, premiumQuota, "PREMIUM");
        sendReadinessNotices(rideId, generalKey, generalQuota, "GENERAL");

        // 2️⃣ 실제 탑승 처리
        popUsers(premiumKey, rideId, premiumQuota, "PREMIUM");
        popUsers(generalKey, rideId, generalQuota, "GENERAL");
    }

    private void sendReadinessNotices(Long rideId,
                                      String queueKey,
                                      int quota,
                                      String type) {

        Long size = redisTemplate.opsForZSet().size(queueKey);
        if (size == null || size == 0) {
            return;
        }

        long maxIndex = Math.min(size - 1, quota * 2L - 1);

        Set<ZSetOperations.TypedTuple<String>> users =
                redisTemplate.opsForZSet().rangeWithScores(queueKey, 0, maxIndex);

        if (users == null || users.isEmpty()) return;

        for (ZSetOperations.TypedTuple<String> tuple : users) {

            String member = tuple.getValue();
            if (member == null) continue;

            Long userId = parseUserId(member);
            if (userId == null) continue;

            long position = redisTemplate.opsForZSet().rank(queueKey, member) + 1;

            if (position <= quota) {
                sendStatusEvent(rideId, userId, type, "READY");
            } else if (position <= quota * 2L) {
                sendStatusEvent(rideId, userId, type, "ALMOST_READY");
            }
        }
    }

    private void popUsers(String queueKey,
                          Long rideId,
                          int quota,
                          String type) {

        for (int i = 0; i < quota; i++) {

            ZSetOperations.TypedTuple<String> popped =
                    redisTemplate.opsForZSet().popMin(queueKey);

            if (popped == null) break;

            String member = popped.getValue();
            Long userId = parseUserId(member);

            cleanupUserIndex(userId, rideId, type);

            logger.info("탑승 처리 완료 - ride={} user={} type={}",
                    rideId, userId, type);
        }
    }

    private void sendStatusEvent(Long rideId,
                                 Long userId,
                                 String type,
                                 String status) {

        String payload =
                "{\"rideId\":" + rideId +
                        ",\"userId\":" + userId +
                        ",\"type\":\"" + type +
                        "\",\"status\":\"" + status + "\"}";

        kafkaTemplate.send(QUEUE_EVENT_TOPIC, rideId.toString(), payload);

        logger.info("이벤트 전송 - ride={} user={} status={}",
                rideId, userId, status);
    }

    private void cleanupUserIndex(Long userId,
                                  Long rideId,
                                  String type) {

        if (userId == null) return;

        String userIndexKey = "user:queues:" + userId;
        String rideType = rideId + ":" + type;

        redisTemplate.opsForZSet().remove(userIndexKey, rideType);
    }

    private Long parseUserId(String member) {
        try {
            String[] parts = Objects.requireNonNull(member).split(":");
            if (parts.length == 2) {
                return Long.parseLong(parts[1]);
            }
        } catch (Exception ignore) {}
        return null;
    }
}
