package com.skala.queue_server.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final String QUEUE_KEY           = "queue:attraction:%d:%s";
    private static final String META_KEY            = "attraction:meta:%d";
    private static final String ACTIVE_ATTRACTIONS  = "attraction:active_ids";
    private static final List<QueueStatus> ACTIVE   = List.of(QueueStatus.WAITING, QueueStatus.AVAILABLE);

    @Value("${queue.defer.max-count:3}")
    private int maxDeferCount;

    private final AttractionQueueRepository repository;
    private final RedisTemplate<String, String> redisTemplate;
    private final AttractionClient attractionClient;
    private final TicketClient ticketClient;

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
        if (!"AVAILABLE".equals(ticketInfo.getEntryStatus())) {
            throw new QueueException(ErrorCode.TICKET_ALREADY_USED);
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
        if (repository.existsByIssuedTicketIdAndStatusIn(issuedTicketId,
                List.of(QueueStatus.WAITING, QueueStatus.AVAILABLE, QueueStatus.COMPLETED))) {
            throw new QueueException(ErrorCode.TICKET_ALREADY_USED);
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

        if (queue.getStatus() != QueueStatus.AVAILABLE) {
            throw new QueueException(ErrorCode.QUEUE_STATUS_NOT_AVAILABLE);
        }
        if (queue.getDeferCount() >= maxDeferCount) {
            throw new QueueException(ErrorCode.DEFER_LIMIT_EXCEEDED);
        }

        String queueKey = String.format(QUEUE_KEY, attractionId, queue.getTicketType().name());
        String metaKey = String.format(META_KEY, attractionId);

        // 미루기 후 새로운 위치를 계산하기 위해 queue 크기 확인
        Long currentQueueSize = redisTemplate.opsForZSet().size(queueKey);
        int queueSizeBefore = currentQueueSize != null ? currentQueueSize.intValue() : 0;

        // Redis에 사용자 추가 (대기 큐의 맨 뒤로)
        // 점수(score)는 현재 타임스탐프로 설정하여 가장 뒤에 배치
        redisTemplate.opsForZSet().add(queueKey, userId.toString(), System.currentTimeMillis());

        // 미루어진 후의 새로운 회차 계산
        // queueSizeBefore: 미루기 전 큐의 다른 사용자 수
        // 이를 기반으로 사용자가 언제쯤 차례가 될지 예상
        int newEstimatedCycleNumber = calcEstimatedCycleNumber(attractionId, metaKey, queue.getTicketType(), queueSizeBefore);

        // 계산된 회차번호가 실제로 존재하는 회차인지 확인 및 cycleId 조회
        // attraction-server 호출 실패 시 null 반환되며, 이는 허용됨
        // (Scheduler가 나중에 처리할 때까지 WAITING 상태 유지)
        Long newAttractionCycleId = resolveAttractionCycleId(attractionId, newEstimatedCycleNumber);

        // DB 업데이트: 상태, 미루기 횟수, 예약된 회차 정보 업데이트
        queue.setStatus(QueueStatus.WAITING);
        queue.setDeferCount(queue.getDeferCount() + 1);
        queue.setAttractionCycleId(newAttractionCycleId);  // 새로운 예상 회차로 업데이트 (핵심!)
        repository.save(queue);

        int newPosition = getPosition(queueKey, userId);
        int estimated   = calcEstimatedMinutes(metaKey, queue.getTicketType(), newPosition);

        // 미루기 과정에서의 상태 변화를 로그로 기록 (모니터링/디버깅용)
        log.info("deferred userId={} attractionId={} deferCount={} oldCycleId={} newCycleId={} newPosition={} newCycleNumber={}",
                userId, attractionId, queue.getDeferCount(), queue.getAttractionCycleId(), newAttractionCycleId, newPosition, newEstimatedCycleNumber);

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
    // 사용자가 AVAILABLE 상태일 때 탑승 버튼을 누르면 호출
    // 탑승 코드 검증 없이 AVAILABLE 상태만 확인하고 COMPLETED로 처리
    @Transactional
    public CompleteResponse complete(Long userId, Long attractionId, Long requesterId) {
        if (!userId.equals(requesterId)) {
            throw new QueueException(ErrorCode.FORBIDDEN);
        }

        AttractionQueue queue = repository
                .findByUserIdAndAttractionIdAndStatusIn(userId, attractionId, ACTIVE)
                .orElseThrow(() -> new QueueException(ErrorCode.QUEUE_NOT_FOUND));

        if (queue.getStatus() != QueueStatus.AVAILABLE) {
            throw new QueueException(ErrorCode.QUEUE_STATUS_NOT_AVAILABLE);
        }

        queue.setStatus(QueueStatus.COMPLETED);
        repository.save(queue);

        log.info("completed userId={} attractionId={} cycleId={}",
                userId, attractionId, queue.getAttractionCycleId());

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
