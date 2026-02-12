package com.skala.queue_server.dto.enqueue;

public record EnqueueResponse(
        long position,                 // 내 순번 (1부터)
        long estimatedWaitMinutes      // 예상 대기 시간(분)
) {}
