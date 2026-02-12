package com.skala.queue_server.dto.status;

public record QueueStatusItem(
        Long rideId,
        String ticketType,          // PREMIUM | GENERAL
        long position,              // 내 순번 (1부터)
        long estimatedWaitMinutes   // 예상 대기 시간(분)
) {}

