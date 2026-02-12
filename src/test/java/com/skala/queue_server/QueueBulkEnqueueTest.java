package com.skala.queue_server;

import com.skala.queue_server.dto.enqueue.EnqueueResponse;
import com.skala.queue_server.service.QueueService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
public class QueueBulkEnqueueTest {

    @Autowired
    private QueueService queueService;

    @Test
    @DisplayName("사용자 1~100 프리미엄 티켓으로 놀이기구 1 대기열 등록")
    void enqueue_users_1_to_100_premium_ride1() {
        long lastPosition = -1;
        for (long userId = 1; userId <= 1000; userId++) {
            EnqueueResponse res = queueService.enqueue(userId, 1L, "PREMIUM");
            // 각 등록 시 순번은 증가해야 함
            Assertions.assertTrue(res.position() >= lastPosition, "순번이 이전 값보다 작아졌습니다");
            lastPosition = res.position();
        }
        // 마지막 사용자는 최소 100번째여야 함
        Assertions.assertTrue(lastPosition >= 100, "마지막 순번이 100보다 작습니다");
    }
}

