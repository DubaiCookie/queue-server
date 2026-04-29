package com.skala.queue_server.service;

import com.skala.queue_server.dto.QueueStatusResponse;
import com.skala.queue_server.dto.WaitingInfoResponse;
import com.skala.queue_server.dto.ws.AllAttractionsWaitEvent;
import com.skala.queue_server.entity.AttractionQueue;
import com.skala.queue_server.entity.QueueStatus;
import com.skala.queue_server.repository.AttractionQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketSchedulerService {

    private static final String ACTIVE_ATTRACTIONS_KEY = "attraction:active_ids";

    private final SimpMessagingTemplate messagingTemplate;
    private final QueueService queueService;
    private final AttractionQueueRepository repository;
    private final RedisTemplate<String, String> redisTemplate;

    // 1분마다 전체 놀이기구 대기 목록 브로드캐스트
    @Scheduled(fixedRate = 60000)
    public void broadcastAllAttractionsWait() {
        try {
            Set<String> ids = redisTemplate.opsForSet().members(ACTIVE_ATTRACTIONS_KEY);
            if (ids == null || ids.isEmpty()) return;

            List<AllAttractionsWaitEvent.AttractionWait> list = ids.stream()
                    .map(id -> {
                        WaitingInfoResponse info = queueService.getWaitingInfo(Long.parseLong(id));
                        return new AllAttractionsWaitEvent.AttractionWait(
                                info.getAttractionId(),
                                info.getWaitingMinutesPremium(),
                                info.getWaitingMinutesBasic(),
                                info.getQueueCountPremium(),
                                info.getQueueCountBasic()
                        );
                    })
                    .collect(Collectors.toList());

            messagingTemplate.convertAndSend("/sub/rides/minutes", new AllAttractionsWaitEvent(list));
            log.info("전체 놀이기구 대기 목록 브로드캐스트 완료 - 놀이기구 수={}", list.size());
        } catch (Exception e) {
            log.error("전체 놀이기구 대기 목록 브로드캐스트 실패", e);
        }
    }

    // 특정 놀이기구 대기 정보 브로드캐스트
    public void broadcastAttractionWait(Long attractionId) {
        try {
            WaitingInfoResponse info = queueService.getWaitingInfo(attractionId);
            messagingTemplate.convertAndSend("/sub/rides/" + attractionId + "/info", info);
            log.debug("놀이기구 대기 정보 브로드캐스트 완료 - attractionId={}", attractionId);
        } catch (Exception e) {
            log.error("놀이기구 대기 정보 브로드캐스트 실패 - attractionId={}", attractionId, e);
        }
    }

    // 1분마다 활성 놀이기구별 대기 정보 브로드캐스트
    @Scheduled(fixedRate = 60000)
    public void scheduledAttractionDetailBroadcast() {
        try {
            Set<String> ids = redisTemplate.opsForSet().members(ACTIVE_ATTRACTIONS_KEY);
            if (ids == null || ids.isEmpty()) return;

            ids.forEach(id -> broadcastAttractionWait(Long.parseLong(id)));
            log.info("활성 놀이기구 상세 정보 브로드캐스트 완료 - 놀이기구 수={}", ids.size());
        } catch (Exception e) {
            log.error("활성 놀이기구 상세 정보 브로드캐스트 실패", e);
        }
    }

    // 특정 사용자 대기열 상태 브로드캐스트
    public void broadcastUserQueueStatus(Long userId) {
        try {
            QueueStatusResponse status = queueService.getStatus(userId, userId);
            messagingTemplate.convertAndSend("/sub/user/" + userId + "/queue-status", status);
            log.debug("사용자 대기열 상태 브로드캐스트 완료 - userId={}", userId);
        } catch (Exception e) {
            log.error("사용자 대기열 상태 브로드캐스트 실패 - userId={}", userId, e);
        }
    }

    // 1분마다 대기 중인 사용자들의 대기열 상태 브로드캐스트
    @Scheduled(fixedRate = 60000)
    public void scheduledUserQueueStatusBroadcast() {
        try {
            List<AttractionQueue> waiting = repository.findByStatusIn(List.of(QueueStatus.WAITING, QueueStatus.AVAILABLE));
            if (waiting.isEmpty()) return;

            List<Long> userIds = waiting.stream()
                    .map(AttractionQueue::getUserId)
                    .distinct()
                    .collect(Collectors.toList());

            userIds.forEach(this::broadcastUserQueueStatus);
            log.info("대기 중인 사용자 대기열 상태 브로드캐스트 완료 - 사용자 수={}", userIds.size());
        } catch (Exception e) {
            log.error("대기 중인 사용자 대기열 상태 브로드캐스트 실패", e);
        }
    }
}
