package com.skala.queue_server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.queue_server.client.AttractionClient;
import com.skala.queue_server.dto.AttractionCycleInfo;
import com.skala.queue_server.dto.WaitingInfoResponse;
import com.skala.queue_server.entity.AttractionQueue;
import com.skala.queue_server.entity.QueueStatus;
import com.skala.queue_server.entity.TicketType;
import com.skala.queue_server.repository.AttractionQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttractionSchedulerService {

    private static final String TOPIC_AVAILABLE     = "queue-available-event";
    private static final String TOPIC_ALMOST_READY  = "queue-almost-ready-event";
    private static final String TOPIC_STATUS        = "queue-status-event";
    private static final String META_KEY            = "attraction:meta:%d";
    private static final String LAST_DISPATCH_KEY   = "attraction:last_dispatch:%d";
    private static final String ALMOST_READY_NOTIFIED_KEY = "queue:almost_ready_notified:%d";
    private static final String ACTIVE_ATTRACTIONS_KEY = "attraction:active_ids";

    @Value("${queue.noshow.timeout-minutes:5}")
    private int noShowTimeoutMinutes;

    @Value("${queue.almost-ready.cycles-before:2}")
    private int almostReadyCyclesBefore;

    private final AttractionQueueRepository repository;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final QueueService queueService;
    private final AttractionClient attractionClient;
    private final ObjectMapper objectMapper;

    // ── WAITING → AVAILABLE 디스패치 (10초마다) ──────────────────────────────
    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void dispatchReadyUsers() {
        Set<String> attractionIds = redisTemplate.opsForSet().members(ACTIVE_ATTRACTIONS_KEY);
        if (attractionIds == null || attractionIds.isEmpty()) return;

        for (String idStr : attractionIds) {
            try {
                dispatchAttraction(Long.parseLong(idStr));
            } catch (Exception e) {
                log.error("dispatch error attractionId={}", idStr, e);
            }
        }
    }

    private void dispatchAttraction(Long attractionId) {
        String metaKey = String.format(META_KEY, attractionId);
        Object cycleSecsObj = redisTemplate.opsForHash().get(metaKey, "cyclingTimeSeconds");
        if (cycleSecsObj == null) return;

        long cyclingTimeMs = Long.parseLong(cycleSecsObj.toString()) * 1000L;
        String lastDispatchKey = String.format(LAST_DISPATCH_KEY, attractionId);
        String lastDispatchStr = redisTemplate.opsForValue().get(lastDispatchKey);
        long lastDispatch = lastDispatchStr == null ? 0L : Long.parseLong(lastDispatchStr);

        if (System.currentTimeMillis() - lastDispatch < cyclingTimeMs) return;

        // attraction-server에서 현재 실제 회차 ID 조회
        AttractionCycleInfo cycleInfo = attractionClient.getCurrentCycle(attractionId);
        Long cycleId = (cycleInfo != null) ? cycleInfo.getAttractionCycleId() : null;

        // PREMIUM → BASIC 순으로 디스패치
        for (TicketType ticketType : TicketType.values()) {
            Object capacityObj = redisTemplate.opsForHash().get(metaKey,
                    ticketType == TicketType.PREMIUM ? "capacityPremium" : "capacityBasic");
            if (capacityObj == null) continue;

            int capacity = Integer.parseInt(capacityObj.toString());
            String queueKey = queueService.getQueueKey(attractionId, ticketType);

            sendAlmostReadyEvents(attractionId, ticketType, capacity, queueKey);

            Set<String> topUsers = redisTemplate.opsForZSet().range(queueKey, 0, capacity - 1);
            if (topUsers == null || topUsers.isEmpty()) continue;

            for (String userIdStr : topUsers) {
                Long userId = Long.parseLong(userIdStr);
                try {
                    makeAvailable(userId, attractionId, ticketType, cycleId);
                    redisTemplate.opsForZSet().remove(queueKey, userIdStr);
                } catch (Exception e) {
                    log.error("makeAvailable error userId={} attractionId={}", userId, attractionId, e);
                }
            }
        }

        redisTemplate.opsForValue().set(lastDispatchKey, String.valueOf(System.currentTimeMillis()));
        log.info("dispatched attractionId={} cycleId={}", attractionId, cycleId);

        sendStatusEvent(attractionId);
    }

    private void sendStatusEvent(Long attractionId) {
        try {
            WaitingInfoResponse info = queueService.getWaitingInfo(attractionId);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("attractionId",          info.getAttractionId());
            event.put("waitingMinutesPremium", info.getWaitingMinutesPremium());
            event.put("waitingMinutesBasic",   info.getWaitingMinutesBasic());
            event.put("queueCountPremium",     info.getQueueCountPremium());
            event.put("queueCountBasic",       info.getQueueCountBasic());
            kafkaTemplate.send(TOPIC_STATUS, attractionId.toString(),
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("queue-status-event send error attractionId={}", attractionId, e);
        }
    }

    private void sendAlmostReadyEvents(Long attractionId, TicketType ticketType, int capacity, String queueKey) {
        if (capacity <= 0 || almostReadyCyclesBefore <= 0) return;

        int startRank = capacity;
        int endRank = (capacity * almostReadyCyclesBefore) - 1;
        if (endRank < startRank) return;

        Set<String> almostReadyUsers = redisTemplate.opsForZSet().range(queueKey, startRank, endRank);
        if (almostReadyUsers == null || almostReadyUsers.isEmpty()) return;

        for (String userIdStr : almostReadyUsers) {
            Long userId = Long.parseLong(userIdStr);
            repository.findFirstByUserIdAndAttractionIdAndTicketTypeAndStatusOrderByCreatedAtDesc(
                    userId, attractionId, ticketType, QueueStatus.WAITING)
                    .ifPresent(queue -> {
                        String notifiedKey = String.format(ALMOST_READY_NOTIFIED_KEY, queue.getAttractionQueueId());
                        Boolean alreadyNotified = redisTemplate.hasKey(notifiedKey);
                        if (Boolean.TRUE.equals(alreadyNotified)) return;

                        redisTemplate.opsForValue().set(notifiedKey, "true");
                        sendAlmostReadyEvent(queue);
                    });
        }
    }

    private void makeAvailable(Long userId, Long attractionId, TicketType ticketType, Long cycleId) {
        repository.findByUserIdAndAttractionIdAndStatusIn(
                userId, attractionId, List.of(QueueStatus.WAITING))
                .ifPresent(queue -> {
                    queue.setStatus(QueueStatus.AVAILABLE);
                    queue.setAttractionCycleId(cycleId);
                    repository.save(queue);
                    sendAvailableEvent(queue);
                });
    }

    private void sendAvailableEvent(AttractionQueue queue) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("attractionQueueId", queue.getAttractionQueueId());
            event.put("userId",            queue.getUserId());
            event.put("attractionId",      queue.getAttractionId());
            event.put("attractionName",    getAttractionName(queue.getAttractionId()));
            event.put("ticketType",        queue.getTicketType().name());
            event.put("cycleId",           queue.getAttractionCycleId());
            event.put("status",            "AVAILABLE");
            kafkaTemplate.send(TOPIC_AVAILABLE, queue.getAttractionId().toString(),
                    objectMapper.writeValueAsString(event));
            queueService.publishUserStatusEvent(queue.getUserId());
            log.info("sent available event userId={} attractionId={} cycleId={}",
                    queue.getUserId(), queue.getAttractionId(), queue.getAttractionCycleId());
        } catch (Exception e) {
            log.error("kafka send error", e);
        }
    }

    private void sendAlmostReadyEvent(AttractionQueue queue) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("attractionQueueId", queue.getAttractionQueueId());
            event.put("userId",            queue.getUserId());
            event.put("attractionId",      queue.getAttractionId());
            event.put("attractionName",    getAttractionName(queue.getAttractionId()));
            event.put("ticketType",        queue.getTicketType().name());
            event.put("status",            "ALMOST_READY");
            event.put("cyclesBefore",      almostReadyCyclesBefore);
            kafkaTemplate.send(TOPIC_ALMOST_READY, queue.getAttractionId().toString(),
                    objectMapper.writeValueAsString(event));
            log.info("sent almost-ready event userId={} attractionId={} cyclesBefore={}",
                    queue.getUserId(), queue.getAttractionId(), almostReadyCyclesBefore);
        } catch (Exception e) {
            log.error("kafka almost-ready send error", e);
        }
    }

    private String getAttractionName(Long attractionId) {
        String metaKey = String.format(META_KEY, attractionId);
        Object cached = redisTemplate.opsForHash().get(metaKey, "attractionName");
        if (cached != null) return cached.toString();
        try {
            var attraction = attractionClient.getAttraction(attractionId);
            return attraction != null ? attraction.getAttractionName() : "attraction-" + attractionId;
        } catch (Exception e) {
            return "attraction-" + attractionId;
        }
    }

    // ── AVAILABLE → NO_SHOW 처리 (1분마다) ───────────────────────────────────
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void processNoShow() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(noShowTimeoutMinutes);
        List<AttractionQueue> expired = repository.findByStatusAndUpdatedAtBefore(
                QueueStatus.AVAILABLE, threshold);

        for (AttractionQueue queue : expired) {
            queue.setStatus(QueueStatus.NO_SHOW);
            repository.save(queue);
            log.info("NO_SHOW userId={} attractionId={}", queue.getUserId(), queue.getAttractionId());
        }
    }

    // ── 놀이기구 메타 등록 (외부에서 호출) ───────────────────────────────────
    public void registerAttractionMeta(Long attractionId, int cyclingTimeSeconds,
                                        int capacityPremium, int capacityBasic) {
        String metaKey = String.format(META_KEY, attractionId);
        redisTemplate.opsForHash().put(metaKey, "cyclingTimeSeconds", String.valueOf(cyclingTimeSeconds));
        redisTemplate.opsForHash().put(metaKey, "capacityPremium",    String.valueOf(capacityPremium));
        redisTemplate.opsForHash().put(metaKey, "capacityBasic",      String.valueOf(capacityBasic));
        redisTemplate.opsForSet().add(ACTIVE_ATTRACTIONS_KEY, attractionId.toString());
        log.info("registered attraction meta attractionId={}", attractionId);
    }
}
