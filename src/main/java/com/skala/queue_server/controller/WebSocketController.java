package com.skala.queue_server.controller;

import com.skala.queue_server.service.WebSocketSchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final WebSocketSchedulerService webSocketSchedulerService;

    // 전체 놀이기구 대기 목록 구독 시 즉시 전송
    @MessageMapping("/rides/minutes")
    public void subscribeAllRides(Principal principal) {
        log.debug("전체 놀이기구 대기 목록 구독 요청 user={}", principal != null ? principal.getName() : "anonymous");
        webSocketSchedulerService.broadcastAllAttractionsWait();
    }

    // 특정 놀이기구 대기 정보 구독 시 즉시 전송
    @MessageMapping("/rides/{attractionId}/info")
    public void subscribeRideInfo(@DestinationVariable Long attractionId, Principal principal) {
        log.debug("놀이기구 대기 정보 구독 요청 attractionId={} user={}", attractionId, principal != null ? principal.getName() : "anonymous");
        webSocketSchedulerService.broadcastAttractionWait(attractionId);
    }

    // 사용자 대기열 상태 구독 시 즉시 전송
    @MessageMapping("/user/{userId}/queue-status")
    public void subscribeUserQueueStatus(@DestinationVariable Long userId, Principal principal) {
        log.debug("사용자 대기열 상태 구독 요청 userId={} user={}", userId, principal != null ? principal.getName() : "anonymous");
        webSocketSchedulerService.broadcastUserQueueStatus(userId);
    }
}
