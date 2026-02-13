package com.skala.queue_server.service;

import com.skala.queue_server.dto.enqueue.EnqueueResponse;
import com.skala.queue_server.dto.ride.RideQueueInfoDto;
import com.skala.queue_server.dto.ride.RideQueueInfoListResponse;
import com.skala.queue_server.dto.ride.RideWaitTimeDto;
import com.skala.queue_server.dto.status.QueueStatusItem;
import com.skala.queue_server.dto.status.QueueStatusListResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class QueueService {

    private static final Logger logger = LoggerFactory.getLogger(QueueService.class);
    private static final String QUEUE_KEY_PREFIX = "queue:ride:";
    private static final String META_KEY_PREFIX = "ride:meta:";
    private static final String USER_INDEX_PREFIX = "user:queues:"; // user:queues:{userId} - ZSET, member=rideId:TYPE, score=joinTime

    private final int userMaxRides = 3;

    private final RedisTemplate<String, String> redisTemplate;

    public EnqueueResponse enqueue(Long userId, Long rideId, String ticketType) {
        // 1) 요청 정규화 및 사용자 제한 검사
        String normalizedType = normalizeTicketType(ticketType);
        validateUserLimit(userId, rideId, normalizedType);

        // 2) 키/멤버/스코어 생성
        String queueKey = buildQueueKey(rideId, normalizedType);
        String member = buildUserMember(userId);
        long score = System.currentTimeMillis();

        // 3) 대기열 등록 + 사용자 인덱스 갱신
        addToQueue(queueKey, member, score);
        upsertUserIndex(userId, rideId, normalizedType, score);

        // 4) 현재 순번 조회
        long position = getPosition(queueKey, member);
        long aheadCount = position - 1;

        // 5) 메타 로드 + 예상 대기시간 계산
        Meta meta = loadRideMeta(rideId);
        long estimatedMinutes = calculateEstimatedMinutes(aheadCount, meta);

        logger.info("예상 대기시간 계산(등록) - 놀이기구={} 현재순번={} 앞사람수={} ridingTime={}초 capacityTotal={} 예상대기(분)={}",
                rideId, position, aheadCount, meta.ridingTimeSeconds, meta.capacityTotal, estimatedMinutes);

        return new EnqueueResponse(position, estimatedMinutes);
    }

    public EnqueueResponse getStatus(Long userId, Long rideId, String ticketType) {
        // 1) 요청 정규화
        String normalizedType = normalizeTicketType(ticketType);

        // 2) 키/멤버 생성
        String queueKey = buildQueueKey(rideId, normalizedType);
        String member = buildUserMember(userId);

        // 3) 현재 순번 조회 (없으면 예외)
        long position = getPosition(queueKey, member);
        long aheadCount = position - 1;

        // 4) 메타 로드 + 예상 대기시간 계산
        Meta meta = loadRideMeta(rideId);
        long estimatedMinutes = calculateEstimatedMinutes(aheadCount, meta);

        logger.info("예상 대기시간 계산(재조회) - 놀이기구={} 현재순번={} 앞사람수={} ridingTime={}초 capacityTotal={} 예상대기(분)={} 키={}",
                rideId, position, aheadCount, meta.ridingTimeSeconds, meta.capacityTotal, estimatedMinutes, queueKey);

        return new EnqueueResponse(position, estimatedMinutes);
    }

    // 사용자 모든 대기열 상태 목록
    public QueueStatusListResponse getAllStatus(Long userId) {
        String userIndexKey = USER_INDEX_PREFIX + userId;
        var tuples = redisTemplate.opsForZSet().rangeWithScores(userIndexKey, 0, -1);
        List<QueueStatusItem> items = new ArrayList<>();
        if (tuples == null || tuples.isEmpty()) {
            logger.info("사용자 대기열 없음 - 사용자={}", userId);
            return new QueueStatusListResponse(userId, items);
        }
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            String rideType = t.getValue();
            if (rideType == null) continue;
            String[] parts = rideType.split(":");
            if (parts.length != 2) continue;
            Long rId = Long.parseLong(parts[0]);
            String type = parts[1];
            try {
                EnqueueResponse s = getStatus(userId, rId, type);
                items.add(new QueueStatusItem(rId, type, s.position(), s.estimatedWaitMinutes()));
            } catch (Exception e) {
                // 대기열에서 이미 팝되어 없어진 경우, 인덱스에서 제거
                removeFromUserIndex(userId, rId, type);
                logger.info("인덱스 정리 - 더 이상 대기하지 않음 사용자={} 놀이기구={} 타입={}", userId, rId, type);
            }
        }
        return new QueueStatusListResponse(userId, items);
    }

     /**
     * 예약 취소 - 대기열에서 사용자 제거
     *
     * @param userId 사용자 ID
     * @param rideId 놀이기구 ID
     * @param ticketType 티켓 타입
     */
    public void cancelReservation(Long userId, Long rideId, String ticketType) {
        logger.info("예약 취소 요청 - 사용자={} 놀이기구={} 티켓타입={}", userId, rideId, ticketType);

        // 1) 티켓 타입 정규화
        String normalizedType = normalizeTicketType(ticketType);

        // 2) 대기열 키와 멤버 생성
        String queueKey = buildQueueKey(rideId, normalizedType);
        String member = buildUserMember(userId);

        // 3) 대기열에서 사용자 제거
        Long removedFromQueue = redisTemplate.opsForZSet().remove(queueKey, member);

        // 4) 사용자 인덱스에서도 제거
        removeFromUserIndex(userId, rideId, normalizedType);

        if (removedFromQueue != null && removedFromQueue > 0) {
            logger.info("대기열 제거 완료 - 사용자={} 놀이기구={} 티켓타입={} 제거된수={}",
                    userId, rideId, normalizedType, removedFromQueue);
        } else {
            logger.warn("대기열에서 사용자를 찾을 수 없음 - 사용자={} 놀이기구={} 티켓타입={} (이미 제거되었거나 존재하지 않음)",
                    userId, rideId, normalizedType);
        }
    }

    /**
     * 모든 놀이기구의 대기열 정보 조회
     * 각 놀이기구의 프리미엄/일반 대기열 인원과 예상 대기시간을 각각 반환
     */
    public RideQueueInfoListResponse getAllRidesQueueInfo() {
        logger.info("모든 놀이기구 대기열 정보 조회 시작");

        Set<String> metaKeys = redisTemplate.keys(META_KEY_PREFIX + "*");
        if (metaKeys == null || metaKeys.isEmpty()) {
            logger.info("놀이기구 메타 정보 없음");
            return new RideQueueInfoListResponse(List.of());
        }

        List<RideQueueInfoDto> rideInfos = new ArrayList<>();

        for (String metaKey : metaKeys) {
            try {
                String rideIdStr = metaKey.replace(META_KEY_PREFIX, "");
                int rideId = Integer.parseInt(rideIdStr);
                Meta meta = loadRideMeta((long) rideId);

                List<RideWaitTimeDto> waitTimes = new ArrayList<>();

                // PREMIUM 대기열
                String premiumKey = buildQueueKey((long) rideId, "PREMIUM");
                Long premiumCount = redisTemplate.opsForZSet().size(premiumKey);
                int premiumWaitingCount = premiumCount != null ? premiumCount.intValue() : 0;
                int premiumWaitMinutes = (int) calculateEstimatedMinutes(premiumWaitingCount, meta);
                waitTimes.add(new RideWaitTimeDto("PREMIUM", premiumWaitingCount, premiumWaitMinutes));

                // GENERAL 대기열
                String generalKey = buildQueueKey((long) rideId, "GENERAL");
                Long generalCount = redisTemplate.opsForZSet().size(generalKey);
                int generalWaitingCount = generalCount != null ? generalCount.intValue() : 0;
                int generalWaitMinutes = (int) calculateEstimatedMinutes(generalWaitingCount, meta);
                waitTimes.add(new RideWaitTimeDto("GENERAL", generalWaitingCount, generalWaitMinutes));

                rideInfos.add(new RideQueueInfoDto(rideId, waitTimes));
                logger.info("놀이기구 대기열 정보 - 놀이기구={} 프리미엄대기={}명({}분) 일반대기={}명({}분)",
                        rideId, premiumWaitingCount, premiumWaitMinutes, generalWaitingCount, generalWaitMinutes);
            } catch (Exception e) {
                logger.error("놀이기구 대기열 정보 조회 실패 - 키={}", metaKey, e);
            }
        }

        logger.info("모든 놀이기구 대기열 정보 조회 완료 - 총 {}개", rideInfos.size());
        return new RideQueueInfoListResponse(rideInfos);
    }

    /**
     * 특정 놀이기구의 대기열 정보 조회
     * 해당 놀이기구의 프리미엄/일반 대기열 인원과 예상 대기시간을 각각 반환
     */
    public RideQueueInfoDto getRideQueueInfo(Long rideId) {
        logger.info("놀이기구 대기열 정보 조회 시작 - 놀이기구={}", rideId);
        try {
            Meta meta = loadRideMeta(rideId);
            List<RideWaitTimeDto> waitTimes = new ArrayList<>();

            // PREMIUM 대기열
            String premiumKey = buildQueueKey(rideId, "PREMIUM");
            Long premiumCount = redisTemplate.opsForZSet().size(premiumKey);
            int premiumWaitingCount = premiumCount != null ? premiumCount.intValue() : 0;
            int premiumWaitMinutes = (int) calculateEstimatedMinutes(premiumWaitingCount, meta);
            waitTimes.add(new RideWaitTimeDto("PREMIUM", premiumWaitingCount, premiumWaitMinutes));

            // GENERAL 대기열
            String generalKey = buildQueueKey(rideId, "GENERAL");
            Long generalCount = redisTemplate.opsForZSet().size(generalKey);
            int generalWaitingCount = generalCount != null ? generalCount.intValue() : 0;
            int generalWaitMinutes = (int) calculateEstimatedMinutes(generalWaitingCount, meta);
            waitTimes.add(new RideWaitTimeDto("GENERAL", generalWaitingCount, generalWaitMinutes));

            logger.info("놀이기구 대기열 정보 조회 완료 - 놀이기구={} 프리미엄대기={}명({}분) 일반대기={}명({}분)",
                    rideId, premiumWaitingCount, premiumWaitMinutes, generalWaitingCount, generalWaitMinutes);
            return new RideQueueInfoDto(rideId.intValue(), waitTimes);
        } catch (Exception e) {
            logger.error("놀이기구 대기열 정보 조회 실패 - 놀이기구={}", rideId, e);
            throw new IllegalStateException("놀이기구 대기열 정보 조회 실패: " + e.getMessage());
        }
    }

    // ===== 책임별 내부 메서드 =====

    // 사용자 대기열 최대 개수 제한 검사
    private void validateUserLimit(Long userId, Long rideId, String normalizedType) {
        String userIndexKey = USER_INDEX_PREFIX + userId;
        String rideType = rideId + ":" + normalizedType;
        Double existing = redisTemplate.opsForZSet().score(userIndexKey, rideType);
        Long currentSize = redisTemplate.opsForZSet().size(userIndexKey);
        if (existing == null && currentSize != null && currentSize >= userMaxRides) {
            logger.info("대기열 등록 거부 - 최대 허용 개수 초과 사용자={} 현재개수={} 한도={}", userId, currentSize, userMaxRides);
            throw new IllegalStateException("사용자 대기열 최대 개수(" + userMaxRides + ")를 초과했습니다");
        }
    }

    // 대기열에 사용자 추가
    private void addToQueue(String queueKey, String member, double score) {
        redisTemplate.opsForZSet().add(queueKey, member, score);
        logger.debug("대기열 등록 - 키={} 멤버={} 스코어={}", queueKey, member, score);
    }

    // 현재 사용자 순번(1부터) 조회
    private long getPosition(String queueKey, String member) {
        Long rank = redisTemplate.opsForZSet().rank(queueKey, member);
        if (rank == null) {
            logger.info("상태 조회 실패 - 대기열에 사용자가 없습니다. 키={} 멤버={}", queueKey, member);
            throw new IllegalStateException("대기열에 사용자가 없습니다");
        }
        return rank + 1;
    }

    // 놀이기구 메타 정보 로드
    private Meta loadRideMeta(Long rideId) {
        String metaKey = META_KEY_PREFIX + rideId;
        Object ridingTimeObj = redisTemplate.opsForHash().get(metaKey, "ridingTimeSeconds");
        Object totalCapacityObj = redisTemplate.opsForHash().get(metaKey, "capacityTotal");
        if (ridingTimeObj == null || totalCapacityObj == null) {
            throw new IllegalStateException("놀이기구 메타 정보 없음");
        }
        return new Meta(Long.parseLong(ridingTimeObj.toString()), Long.parseLong(totalCapacityObj.toString()));
    }

    // 예상 대기시간(분) 계산
    private long calculateEstimatedMinutes(long aheadCount, Meta meta) {
        long cycleIndex = aheadCount / meta.capacityTotal;
        long estimatedSeconds = cycleIndex * meta.ridingTimeSeconds;
        if (estimatedSeconds <= 0) return 0;
        if (estimatedSeconds < 60) return 1;
        return (long) Math.ceil(estimatedSeconds / 60.0);
    }

    private void upsertUserIndex(Long userId, Long rideId, String ticketType, double score) {
        String userIndexKey = USER_INDEX_PREFIX + userId;
        String rideType = rideId + ":" + ticketType;
        redisTemplate.opsForZSet().add(userIndexKey, rideType, score);
        logger.debug("사용자 인덱스 추가 - 사용자={} 항목={} 스코어={}", userId, rideType, score);
    }

    public void removeFromUserIndex(Long userId, Long rideId, String ticketType) {
        String userIndexKey = USER_INDEX_PREFIX + userId;
        String rideType = rideId + ":" + ticketType;
        redisTemplate.opsForZSet().remove(userIndexKey, rideType);
    }

    private String normalizeTicketType(String ticketType) {
        if (ticketType == null) return "GENERAL";
        String t = ticketType.trim().toUpperCase();
        return ("PREMIUM".equals(t)) ? "PREMIUM" : "GENERAL";
    }

    private String buildQueueKey(Long rideId, String ticketType) {
        return QUEUE_KEY_PREFIX + rideId + ":" + ticketType;
    }

    private String buildUserMember(Long userId) {
        return "user:%d".formatted(userId);
    }

    // 메타 정보를 담는 간단한 내부 클래스
    private record Meta(long ridingTimeSeconds, long capacityTotal) {}
}
