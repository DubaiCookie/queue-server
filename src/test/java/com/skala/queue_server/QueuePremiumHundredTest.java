package com.skala.queue_server;

import com.skala.queue_server.service.QueueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

@SpringBootTest
public class QueuePremiumHundredTest {

    @Autowired
    QueueService queueService;

    @Autowired
    RedisTemplate<String, String> redisTemplate;

    @AfterEach
    void cleanup() {
        // 테스트 후 프리미엄 대기열과 사용자 인덱스 간단 정리
        redisTemplate.delete("queue:ride:1:PREMIUM");
        for (long userId = 1; userId <= 100; userId++) {
            redisTemplate.delete("user:notify:" + userId + ":1:PREMIUM:upcoming");
            redisTemplate.delete("user:queues:" + userId);
        }
    }

    @Test
    void enqueue_users_1_to_100_premium_ride1() {
        Long rideId = 1L;
        for (long userId = 1; userId <= 100; userId++) {
            queueService.enqueue(userId, rideId, "PREMIUM");
        }
        Long size = redisTemplate.opsForZSet().size("queue:ride:" + rideId + ":PREMIUM");
        Assertions.assertEquals(100L, size, "프리미엄 대기열 ZSET 크기가 100이어야 합니다");
    }
}

