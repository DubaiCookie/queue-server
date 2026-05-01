package com.skala.queue_server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.queue_server.client.AttractionClient;
import com.skala.queue_server.client.TicketClient;
import com.skala.queue_server.dto.*;
import com.skala.queue_server.entity.AttractionQueue;
import com.skala.queue_server.entity.QueueStatus;
import com.skala.queue_server.entity.TicketType;
import com.skala.queue_server.exception.ErrorCode;
import com.skala.queue_server.exception.QueueException;
import com.skala.queue_server.repository.AttractionQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final String QUEUE_KEY           = "queue:attraction:%d:%s";
    private static final String META_KEY            = "attraction:meta:%d";
    private static final String ACTIVE_ATTRACTIONS  = "attraction:active_ids";
    private static final String ALMOST_READY_NOTIFIED_KEY = "queue:almost_ready_notified:%d";
    private static final String TOPIC_USER_STATUS   = "queue-user-status-event";
    private static final List<QueueStatus> ACTIVE   = List.of(QueueStatus.WAITING, QueueStatus.AVAILABLE);

    @Value("${queue.defer.max-count:3}")
    private int maxDeferCount;

    @Value("${queue.defer.cycles:3}")
    private int deferCycles;

    private final AttractionQueueRepository repository;
    private final RedisTemplate<String, String> redisTemplate;
    private final AttractionClient attractionClient;
    private final TicketClient ticketClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // ── 대기열 등록 ──────────────────────────────────────────────────────────
    @Transactional
    public EnqueueResponse enqueue(Long userId, Long attractionId, Long issuedTicketId) {
        // ticket-server에서 티켓 소유권/유효성 검증 및 ticketType 조회
        IssuedTicketValidationResponse ticketInfo = ticketClient.getIssuedTicket(issuedTicketId);
        if (ticketInfo == null) {
            throw new QueueException(ErrorCode.ISSUED_TICKET_NOT_FOUND);
        }
        if (!userId.equals(ticketInfo.getOwnerUserId())) {
            throw new QueueException(ErrorCode.FORBIDDEN);
        }
        if (!"USED".equals(ticketInfo.getEntryStatus())) {
            throw new QueueException(ErrorCode.TICKET_NOT_ENTERED);
        }
        TicketType ticketType = parseTicketType(ticketInfo.getTicketType());

        String metaKey = String.format(META_KEY, attractionId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(metaKey))) {
            AttractionResponse attraction = attractionClient.getAttraction(attractionId);
            if (attraction == null) {
                throw new QueueException(ErrorCode.ATTRACTION_NOT_FOUND);
            }
            redisTemplate.opsForHash().put(metaKey, "cyclingTimeSeconds", String.valueOf(attraction.getRidingTime()));
            redisTemplate.opsForHash().put(metaKey, "capacityPremium",    String.valueOf(attraction.getCapacityPremium()));
            redisTemplate.opsForHash().put(metaKey, "capacityBasic",      String.valueOf(attraction.getCapacityBasic()));
            redisTemplate.opsForHash().put(metaKey, "attractionName",     attraction.getAttractionName());
            redisTemplate.opsForSet().add(ACTIVE_ATTRACTIONS, attractionId.toString());
            log.info("cached attraction meta from attraction-server attractionId={}", attractionId);
        }
        if (repository.existsByUserIdAndAttractionIdAndStatusIn(userId, attractionId, ACTIVE)) {
            throw new QueueException(ErrorCode.ALREADY_IN_QUEUE);
        }
        String queueKey = String.format(QUEUE_KEY, attractionId, ticketType.name());

        // 내 대기 순번 계산을 위해 추가 전 현재 크기 확인
        Long currentQueueSize = redisTemplate.opsForZSet().size(queueKey);
        int queueSizeBefore = currentQueueSize != null ? currentQueueSize.intValue() : 0;

        // 탑승 예정 회차 계산 → attractionCycleId 미리 확정
        int estimatedCycleNumber = calcEstimatedCycleNumber(attractionId, metaKey, ticketType, queueSizeBefore);
        Long attractionCycleId = resolveAttractionCycleId(attractionId, estimatedCycleNumber);

        redisTemplate.opsForZSet().add(queueKey, userId.toString(), System.currentTimeMillis());

        int position  = getPosition(queueKey, userId);
        int estimated = calcEstimatedMinutes(metaKey, ticketType, position);

        AttractionQueue queue = AttractionQueue.builder()
                .userId(userId)
                .attractionId(attractionId)
                .issuedTicketId(issuedTicketId)
                .ticketType(ticketType)
                .status(QueueStatus.WAITING)
                .attractionCycleId(attractionCycleId)
                .deferCount(0)
                .build();
        repository.save(queue);

        publishUserStatusEvent(userId);

        return new EnqueueResponse(position, estimated, estimatedCycleNumber);
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
                    int position    = q.getStatus() == QueueStatus.AVAILABLE ? 0 : getPosition(queueKey, userId);
                    int estimated   = calcEstimatedMinutes(metaKey, q.getTicketType(), position);
                    return new QueueStatusItem(
                            q.getAttractionId(),
                            getAttractionName(q.getAttractionId()),
                            q.getTicketType().name(),
                            q.getStatus().name(),
                            position,
                            estimated,
                            q.getDeferCount()
                    );
                }).toList();

        return new QueueStatusResponse(userId, items);
    }

    // ── 대기 미루기 ───────────────────────────────────────────────────────────
    // 대기 중인 사용자가 현재 탈 차례를 미루고 나중으로 이동시키는 기능
    //
    // 프로세스:
    // 1. 사용자가 AVAILABLE(차례가 됨) 상태에서만 미루기 가능
    // 2. 미루기 전 큐 크기 기반으로 새로운 탑승 회차 계산
    // 3. 계산된 회차번호로 attraction-server에서 cycleId 조회
    // 4. DB에 새로운 회차 정보와 함께 저장
    // 5. Scheduler가 10초마다 사용자의 예상 회차 도달 여부 확인
    // 6. 도달 시 자동으로 AVAILABLE 상태로 전환 및 rideCode 발급
    //
    // 회차 계산 공식:
    //   새 회차 = 현재 회차 + floor(미루기_전_큐_크기 / 수용_인원)
    //
    // 예시:
    //   - 현재 회차: 5, 큐: 24명, 용량: 10
    //   - 새 회차: 5 + (24/10) = 5 + 2 = 7
    //   - 사용자는 7회차에서 탑승 예상
    @Transactional
    public DeferResponse defer(Long userId, Long attractionId, Long requesterId) {
        if (!userId.equals(requesterId)) {
            throw new QueueException(ErrorCode.FORBIDDEN);
        }

        AttractionQueue queue = findActive(userId, attractionId);

        if (queue.getDeferCount() >= maxDeferCount) {
            throw new QueueException(ErrorCode.DEFER_LIMIT_EXCEEDED);
        }

        String queueKey = String.format(QUEUE_KEY, attractionId, queue.getTicketType().name());
        String metaKey = String.format(META_KEY, attractionId);

        int capacity = getCapacity(metaKey, queue.getTicketType());
        int newPosition = moveBackByCycles(queueKey, userId, capacity, deferCycles);
        int newEstimatedCycleNumber = calcEstimatedCycleNumber(
                attractionId, metaKey, queue.getTicketType(), Math.max(newPosition - 1, 0));
        Long newAttractionCycleId = resolveAttractionCycleId(attractionId, newEstimatedCycleNumber);

        queue.setStatus(QueueStatus.WAITING);
        queue.setDeferCount(queue.getDeferCount() + 1);
        queue.setAttractionCycleId(newAttractionCycleId);
        repository.save(queue);

        int estimated   = calcEstimatedMinutes(metaKey, queue.getTicketType(), newPosition);
        redisTemplate.delete(String.format(ALMOST_READY_NOTIFIED_KEY, queue.getAttractionQueueId()));

        log.info("deferred userId={} attractionId={} deferCount={} cycles={} newCycleId={} newPosition={} newCycleNumber={}",
                userId, attractionId, queue.getDeferCount(), deferCycles, newAttractionCycleId, newPosition, newEstimatedCycleNumber);

        publishUserStatusEvent(userId);

        return new DeferResponse(attractionId, newPosition, queue.getDeferCount(), maxDeferCount, deferCycles, estimated);
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

        publishUserStatusEvent(userId);

        return new CancelResponse("대기열 취소 완료", attractionId);
    }

    // ── 탑승 완료 ─────────────────────────────────────────────────────────────
    // 사용자가 AVAILABLE 상태일 때 탑승 버튼을 누르면 호출
    // 탑승 코드 검증 없이 AVAILABLE 상태만 확인하고 COMPLETED로 처리
    //
    // 추가 동작: COMPLETED 처리 직후 attraction-server에 회차 얼굴 분석을 즉시 트리거한다.
    // 이렇게 해야 사용자가 "탑승 완료"를 누른 시점에 사진 분석 파이프라인이 곧장 시작되어
    // 실제 회차 종료 시각(cycle endTime)까지 기다리지 않고도 사진을 받을 수 있다.
    // 단체 사진에 사용자가 포함되었는지 검증하는 얼굴 매칭 로직은 분석 파이프라인 내부에서 그대로 유지된다.
    @Transactional
    public CompleteResponse complete(Long userId, Long attractionId, Long requesterId) {
        if (!userId.equals(requesterId)) {
            throw new QueueException(ErrorCode.FORBIDDEN);
        }

        AttractionQueue queue = repository
                .findByUserIdAndAttractionIdAndStatusIn(userId, attractionId, ACTIVE)
                .orElseGet(() -> repository
                        .findFirstByUserIdAndAttractionIdAndStatusOrderByUpdatedAtDesc(
                                userId, attractionId, QueueStatus.COMPLETED)
                        .orElseThrow(() -> new QueueException(ErrorCode.QUEUE_NOT_FOUND)));

        if (queue.getStatus() == QueueStatus.COMPLETED) {
            throw new QueueException(ErrorCode.QUEUE_ALREADY_COMPLETED);
        }

        if (queue.getStatus() != QueueStatus.AVAILABLE) {
            throw new QueueException(ErrorCode.QUEUE_STATUS_NOT_AVAILABLE);
        }

        queue.setStatus(QueueStatus.COMPLETED);
        repository.save(queue);

        log.info("completed userId={} attractionId={} cycleId={}",
                userId, attractionId, queue.getAttractionCycleId());

        publishUserStatusEvent(userId);

        // fire-and-forget: 사진 분석 트리거 실패가 탑승 완료 응답을 막지 않도록 비동기 처리
        attractionClient.triggerCycleAnalysis(queue.getAttractionCycleId());

        return new CompleteResponse("탑승 완료", attractionId);
    }

    public void publishUserStatusEvent(Long userId) {
        try {
            QueueStatusResponse status = getStatus(userId, userId);
            kafkaTemplate.send(TOPIC_USER_STATUS, userId.toString(), objectMapper.writeValueAsString(status));
        } catch (Exception e) {
            log.warn("queue-user-status-event send error userId={}: {}", userId, e.getMessage());
        }
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

    private int moveBackByCycles(String queueKey, Long userId, int capacity, int cycles) {
        String member = userId.toString();
        Long currentRankObj = redisTemplate.opsForZSet().rank(queueKey, member);
        int currentRank = currentRankObj == null ? -1 : currentRankObj.intValue();

        redisTemplate.opsForZSet().remove(queueKey, member);

        Set<String> currentMembers = redisTemplate.opsForZSet().range(queueKey, 0, -1);
        List<String> orderedMembers = new ArrayList<>(currentMembers == null ? List.of() : currentMembers);

        int shift = Math.max(capacity, 1) * Math.max(cycles, 1);
        int targetIndex = Math.min(Math.max(currentRank, -1) + shift, orderedMembers.size());
        if (targetIndex < 0) targetIndex = 0;

        orderedMembers.add(targetIndex, member);

        long baseScore = System.currentTimeMillis();
        for (int i = 0; i < orderedMembers.size(); i++) {
            redisTemplate.opsForZSet().add(queueKey, orderedMembers.get(i), baseScore + i);
        }

        return targetIndex + 1;
    }

    private int getCapacity(String metaKey, TicketType ticketType) {
        try {
            Object capacityObj = redisTemplate.opsForHash().get(metaKey,
                    ticketType == TicketType.PREMIUM ? "capacityPremium" : "capacityBasic");
            if (capacityObj == null) return 1;
            return Math.max(Integer.parseInt(capacityObj.toString()), 1);
        } catch (Exception e) {
            return 1;
        }
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

    private Long resolveAttractionCycleId(Long attractionId, int estimatedCycleNumber) {
        if (estimatedCycleNumber <= 0) return null;
        try {
            AttractionCycleInfo cycle = attractionClient.getCycleByNumber(
                    attractionId, java.time.LocalDate.now().toString(), estimatedCycleNumber);
            return cycle != null ? cycle.getAttractionCycleId() : null;
        } catch (Exception e) {
            log.warn("Failed to resolve attractionCycleId for attraction {} cycle {}", attractionId, estimatedCycleNumber);
            return null;
        }
    }

    private int calcEstimatedCycleNumber(Long attractionId, String metaKey, TicketType ticketType, int queueSizeBefore) {
        try {
            AttractionCycleInfo currentCycle = attractionClient.getCurrentCycle(attractionId);
            if (currentCycle == null || currentCycle.getCycleNumber() == null) return 0;

            Object capacityObj = redisTemplate.opsForHash().get(metaKey,
                    ticketType == TicketType.PREMIUM ? "capacityPremium" : "capacityBasic");
            if (capacityObj == null) return currentCycle.getCycleNumber();

            int capacity = Integer.parseInt(capacityObj.toString());
            if (capacity <= 0) return currentCycle.getCycleNumber();

            int cyclesAway = queueSizeBefore / capacity;
            return currentCycle.getCycleNumber() + cyclesAway;
        } catch (Exception e) {
            log.warn("Failed to calculate estimated cycle for attraction {}: {}", attractionId, e.getMessage());
            return 0;
        }
    }

    private String getAttractionName(Long attractionId) {
        String metaKey = String.format(META_KEY, attractionId);
        Object cached = redisTemplate.opsForHash().get(metaKey, "attractionName");
        if (cached != null) return cached.toString();
        AttractionResponse attraction = attractionClient.getAttraction(attractionId);
        return attraction != null ? attraction.getAttractionName() : "attraction-" + attractionId;
    }

    // ── 놀이기구 대기 정보 조회 (attraction-server용) ────────────────────────
    public WaitingInfoResponse getWaitingInfo(Long attractionId) {
        // PREMIUM 대기열
        String queueKeyPremium = String.format(QUEUE_KEY, attractionId, TicketType.PREMIUM.name());
        Long premiumQueueSize = redisTemplate.opsForZSet().size(queueKeyPremium);
        int premiumCount = premiumQueueSize != null ? premiumQueueSize.intValue() : 0;
        int premiumMinutes = calcEstimatedMinutes(getMetaKey(attractionId), TicketType.PREMIUM, premiumCount);

        // BASIC 대기열
        String queueKeyBasic = String.format(QUEUE_KEY, attractionId, TicketType.BASIC.name());
        Long basicQueueSize = redisTemplate.opsForZSet().size(queueKeyBasic);
        int basicCount = basicQueueSize != null ? basicQueueSize.intValue() : 0;
        int basicMinutes = calcEstimatedMinutes(getMetaKey(attractionId), TicketType.BASIC, basicCount);

        log.info("waiting info attractionId={} premium=(count={}, minutes={}) basic=(count={}, minutes={})",
                attractionId, premiumCount, premiumMinutes, basicCount, basicMinutes);

        return new WaitingInfoResponse(
                attractionId,
                premiumMinutes,
                basicMinutes,
                premiumCount,
                basicCount
        );
    }

    public String getQueueKey(Long attractionId, TicketType ticketType) {
        return String.format(QUEUE_KEY, attractionId, ticketType.name());
    }

    public String getMetaKey(Long attractionId) {
        return String.format(META_KEY, attractionId);
    }
}
