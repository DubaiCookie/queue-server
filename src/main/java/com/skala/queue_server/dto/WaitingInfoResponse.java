package com.skala.queue_server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 놀이기구의 대기 정보 응답 DTO
 */
@Getter
@AllArgsConstructor
public class WaitingInfoResponse {
    private Long attractionId;
    private Integer waitingMinutesPremium;
    private Integer waitingMinutesBasic;
    private Integer queueCountPremium;
    private Integer queueCountBasic;
}

