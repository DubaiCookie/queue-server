package com.skala.queue_server.dto.ride;

/**
 * 놀이기구의 티켓 타입별 대기 시간 정보
 *
 * @param ticketType 티켓 타입 (PREMIUM 또는 GENERAL)
 * @param waitingCount 대기 중인 인원 수
 * @param estimatedWaitMinutes 예상 대기 시간 (분)
 */
public record RideWaitTimeDto(
        String ticketType,
        int waitingCount,
        int estimatedWaitMinutes
) {
}

