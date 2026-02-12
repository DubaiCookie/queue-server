package com.skala.queue_server.dto.ride;

import java.util.List;

/**
 * 놀이기구의 전체 대기열 정보
 *
 * @param rideId 놀이기구 ID
 * @param waitTimes 티켓 타입별 대기 시간 리스트 (PREMIUM, GENERAL)
 */
public record RideQueueInfoDto(
        int rideId,
        List<RideWaitTimeDto> waitTimes
) {
}

