package com.skala.queue_server.dto.ride;

import java.util.List;

/**
 * 모든 놀이기구의 대기열 정보 응답 (rideId, 예상 대기 시간 리스트)
 *
 * @param rides 놀이기구별 대기열 정보 리스트
 */
public record RideQueueInfoListResponse(
        List<RideQueueInfoDto> rides
) {
}
