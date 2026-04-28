package com.skala.queue_server.service;

import com.skala.queue_server.dto.*;
import com.skala.queue_server.entity.AttractionQueue;
import com.skala.queue_server.entity.QueueStatus;
import com.skala.queue_server.entity.TicketType;
import com.skala.queue_server.exception.ErrorCode;
import com.skala.queue_server.exception.QueueException;
import com.skala.queue_server.repository.AttractionQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QueueService {

    private static final String QUEUE_KEY = "queue:attraction:%d:%s";
    private static final String META_KEY  = "attraction:meta:%d";
    private static final List<QueueStatus> ACTIVE = List.of(QueueStatus.WAITING, QueueStatus.AVAILABLE);

    @Value("${queue.defer.max-count:3}")
    private int maxDeferCount;

    private final AttractionQueueRepository repository;
    private final RedisTemplate<String, String> redisTemplate;

    // ── 대기열 등록 ──────────────────────────────────────────────────────────
    @Transactional
    public EnqueueResponse enqueue(Long userId, Long attractionId, Long issuedTicketId, String ticketTypeStr) {
        TicketType ticketType = parseTicketType(ticketTypeStr);

        String metaKey = String.format(META_KEY, attractionId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(metaKey))) {
            throw new QueueException(ErrorCode.ATTRACTION_NOT_FOUND);
        }
        if (repository.existsByUserIdAndAttractionIdAndStatusIn(userId, attractionId, ACTIVE)) {
            throw new QueueException(ErrorCode.ALREADY_IN_QUEUE);
        }
        if (repository.existsByIssuedTicketIdAndStatusIn(issuedTicketId,
                List.of(QueueStatus.WAITING, QueueStatus.AVAILABLE, QueueStatus.COMPLETED))) {
            throw new QueueException(ErrorCode.TICKET_ALREADY_USED);
        }

        AttractionQueue queue = AttractionQueue.builder()
                .userId(userId)
                .attractionId(attractionId)
                .issuedTicketId(issuedTicketId)
                .ticketType(ticketType)
                .status(QueueStatus.WAITING)
                .deferCount(0)
                .build();
        repository.save(queue);

        String queueKey = String.format(QUEUE_KEY, attractionId, ticketType.name());
        redisTemplate.opsForZSet().add(queueKey, userId.toString(), System.currentTimeMillis());

        int position  = getPosition(queueKey, userId);
        int estimated = calcEstimatedMinutes(metaKey, ticketType, position);
        return new EnqueueResponse(position, estimated);
    }

    // ── 대기열 상태 조회 ──────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public QueueStatusResponse getStatus(Long userId, Long requesterId) {
        if (!userId.equals(requesterId)) {
            throw new QueueException(ErrorCode.FORBIDDEN);
        }

        List<QueueStatusItem> items = repository.findByUserIdAndStatusIn(userId, ACTIVE)
                .stream().map(q -> {
                    String queueKey = String.format(QUEUE_KEY, q.getAttractionId(), q.getTicketType().name());
                    String metaKey  = String.format(META_KEY, q.getAttractionId());
                    int position    = getPosition(queueKey, userId);
                    int estimated   = calcEstimatedMinutes(metaKey, q.getTicketType(), position);
                    return new QueueStatusItem(
                            q.getAttractionId(),
                            getAttractionName(q.getAttractionId()),
                            q.getTicketType().name(),
                            position,
                            estimated
                    );
                }).toList();

        return new QueueStatusResponse(userId, items);
    }

    // ── 대기 미루기 ───────────────────────────────────────────────────────────
    @Transactional
    public DeferResponse defer(Long userId, Long attractionId, Long requesterId) {
        if (!userId.equals(requesterId)) {
            throw new QueueException(ErrorCode.FORBIDDEN);
        }

        AttractionQueue queue = findActive(userId, attractionId);

        if (queue.getStatus() != QueueStatus.AVAILABLE) {
            throw new QueueException(ErrorCode.QUEUE_STATUS_NOT_AVAILABLE);
        }
        if (queue.getDeferCount() >= maxDeferCount) {
            throw new QueueException(ErrorCode.DEFER_LIMIT_EXCEEDED);
        }

        String queueKey = String.format(QUEUE_KEY, attractionId, queue.getTicketType().name());
        redisTemplate.opsForZSet().add(queueKey, userId.toString(), System.currentTimeMillis());

        queue.setStatus(QueueStatus.WAITING);
        queue.setDeferCount(queue.getDeferCount() + 1);
        queue.setRideCode(null);
        queue.setAttractionCycleId(null);
        repository.save(queue);

        int newPosition = getPosition(queueKey, userId);
        String metaKey  = String.format(META_KEY, attractionId);
        int estimated   = calcEstimatedMinutes(metaKey, queue.getTicketType(), newPosition);
        return new DeferResponse(attractionId, newPosition, queue.getDeferCount(), estimated);
    }

    // ── 대기열 취소 ───────────────────────────────────────────────────────────
    @Transactional
    public CancelResponse cancel(Long userId, Long attractionId, Long requesterId) {
        if (!userId.equals(requesterId)) {
            throw new QueueException(ErrorCode.FORBIDDEN);
        }

        AttractionQueue queue = findActive(userId, attractionId);

        if (queue.getStatus() == QueueStatus.COMPLETED) {
            throw new QueueException(ErrorCode.QUEUE_ALREADY_COMPLETED);
        }

        String queueKey = String.format(QUEUE_KEY, attractionId, queue.getTicketType().name());
        redisTemplate.opsForZSet().remove(queueKey, userId.toString());

        queue.setStatus(QueueStatus.CANCELED);
        repository.save(queue);

        return new CancelResponse("대기열 취소 완료", attractionId);
    }

    // ── 탑승 완료 ─────────────────────────────────────────────────────────────
    @Transactional
    public CompleteResponse complete(Long userId, Long attractionId, String rideCode, Long requesterId) {
        if (!userId.equals(requesterId)) {
            throw new QueueException(ErrorCode.FORBIDDEN);
        }

        AttractionQueue queue = repository
                .findByUserIdAndAttractionIdAndStatusIn(userId, attractionId, ACTIVE)
                .orElseThrow(() -> new QueueException(ErrorCode.QUEUE_NOT_FOUND));

        if (queue.getStatus() == QueueStatus.COMPLETED) {
            throw new QueueException(ErrorCode.QUEUE_ALREADY_COMPLETED);
        }
        if (queue.getStatus() != QueueStatus.AVAILABLE) {
            throw new QueueException(ErrorCode.QUEUE_STATUS_NOT_AVAILABLE);
        }
        if (!rideCode.equals(queue.getRideCode())) {
            throw new QueueException(ErrorCode.INVALID_RIDE_CODE);
        }

        queue.setStatus(QueueStatus.COMPLETED);
        repository.save(queue);

        return new CompleteResponse("탑승 완료", attractionId);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────
    private AttractionQueue findActive(Long userId, Long attractionId) {
        return repository.findByUserIdAndAttractionIdAndStatusIn(userId, attractionId, ACTIVE)
                .orElseThrow(() -> new QueueException(ErrorCode.QUEUE_NOT_FOUND));
    }

    private int getPosition(String queueKey, Long userId) {
        Long rank = redisTemplate.opsForZSet().rank(queueKey, userId.toString());
        return rank == null ? 0 : (int) (rank + 1);
    }

    private int calcEstimatedMinutes(String metaKey, TicketType ticketType, int position) {
        try {
            Object cycleSecsObj = redisTemplate.opsForHash().get(metaKey, "cyclingTimeSeconds");
            Object capacityObj  = redisTemplate.opsForHash().get(metaKey,
                    ticketType == TicketType.PREMIUM ? "capacityPremium" : "capacityBasic");
            if (cycleSecsObj == null || capacityObj == null) return 0;
            int cycleSecs = Integer.parseInt(cycleSecsObj.toString());
            int capacity  = Integer.parseInt(capacityObj.toString());
            if (capacity <= 0) return 0;
            return (int) Math.ceil((double) position / capacity * cycleSecs / 60.0);
        } catch (Exception e) {
            return 0;
        }
    }

    private TicketType parseTicketType(String value) {
        try {
            return TicketType.valueOf(value.toUpperCase());
        } catch (Exception e) {
            throw new QueueException(ErrorCode.INVALID_TICKET_TYPE);
        }
    }

    private String getAttractionName(Long attractionId) {
        // TODO: attraction-server REST 호출로 이름 조회
        return "attraction-" + attractionId;
    }

    public String generateRideCode() {
        return "RIDE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    public String getQueueKey(Long attractionId, TicketType ticketType) {
        return String.format(QUEUE_KEY, attractionId, ticketType.name());
    }

    public String getMetaKey(Long attractionId) {
        return String.format(META_KEY, attractionId);
    }
}
