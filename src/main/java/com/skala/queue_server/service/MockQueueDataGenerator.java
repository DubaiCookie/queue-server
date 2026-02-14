package com.skala.queue_server.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 프론트엔드 테스트를 위한 가짜 대기열 데이터 생성기 - 휴일 피크 타임 시뮬레이션
 *
 * [목표]
 * - 실제 휴일 놀이공원의 붐비는 상황 재현
 * - 항상 충분한 대기 인원 유지로 현실적인 테스트 환경 제공
 *
 * [동작]
 * - 서버 시작 시: 인기도별 20~80분 대기 시간으로 초기 대기열 생성
 * - 30초마다: 대기열이 최소 기준 이하로 떨어지면 자동 보충
 *
 * [인기도별 대기 시간]
 * - 높은 인기(1,5,9,15,20): 프리미엄 30~60분, 일반 40~80분
 * - 보통 인기: 프리미엄 20~40분, 일반 25~50분
 * - 낮은 인기(6,13,17): 프리미엄 15~25분, 일반 20~35분
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "queue.mock.enabled", havingValue = "true")
public class MockQueueDataGenerator {

    private static final Logger logger = LoggerFactory.getLogger(MockQueueDataGenerator.class);
    private static final String QUEUE_KEY_PREFIX = "queue:ride:";
    private static final Random random = new Random();

    private final RedisTemplate<String, String> redisTemplate;
    private final AtomicLong userIdCounter = new AtomicLong(100000);

    @Value("${queue.mock.initial-delay-seconds:5}")
    private int initialDelaySeconds;

    /**
     * 서버 시작 시 초기 가짜 대기열 데이터 생성 - 휴일 피크 타임 시뮬레이션
     */
    @PostConstruct
    @Async
    public void initializeMockData() {
        try {
            logger.info("🎡 === 휴일 피크 타임 대기열 초기화 시작 ({}초 후) ===", initialDelaySeconds);
            TimeUnit.SECONDS.sleep(initialDelaySeconds);

            int totalUsers = 0;
            for (int rideId = 1; rideId <= 20; rideId++) {
                totalUsers += createInitialQueue(rideId);
            }

            logger.info("🎢 === 휴일 대기열 초기화 완료 - 총 {}명 대기 중 ===", totalUsers);
            logger.info("🔄 === 30초마다 자동 보충 시작 (대기열 유지) ===");

        } catch (InterruptedException e) {
            logger.error("초기 데이터 생성 중 인터럽트 발생", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("초기 데이터 생성 중 오류 발생", e);
        }
    }

    /**
     * 30초마다 대기열 확인 및 보충 (휴일 피크 타임 - 사람이 계속 몰림)
     */
    @Scheduled(fixedRate = 30000, initialDelay = 40000) // 30초마다, 초기 시작 후 40초 뒤부터
    public void refillQueues() {
        try {
            int totalAdded = 0;
            for (int rideId = 1; rideId <= 20; rideId++) {
                totalAdded += refillQueueIfNeeded(rideId);
            }

            if (totalAdded > 0) {
                logger.info("🎢 대기열 자동 보충 완료 - 총 {}명 추가됨 (휴일 피크 타임 유지)", totalAdded);
            }
        } catch (Exception e) {
            logger.error("대기열 보충 중 오류 발생", e);
        }
    }

    /**
     * 특정 놀이기구의 초기 대기열 생성
     * 휴일 피크 시간대 기준 - 현실적인 대기 시간 유지
     */
    private int createInitialQueue(int rideId) {
        // 놀이기구 메타 정보 기반 계산
        RideCapacity capacity = getRideCapacity(rideId);
        int popularity = getPopularity(rideId);

        // 목표 대기 시간 (분) 설정 - 휴일 피크 타임 기준
        int targetPremiumMinutes;
        int targetGeneralMinutes;

        switch (popularity) {
            case 3: // 높은 인기 - 30~60분 대기 (롤러코스터, 인기 어트랙션)
                targetPremiumMinutes = 30 + random.nextInt(31); // 30~60분
                targetGeneralMinutes = 40 + random.nextInt(41); // 40~80분
                break;
            case 1: // 낮은 인기 - 15~25분 대기 (조용한 놀이기구)
                targetPremiumMinutes = 15 + random.nextInt(11); // 15~25분
                targetGeneralMinutes = 20 + random.nextInt(16); // 20~35분
                break;
            default: // 보통 인기 - 20~40분 대기
                targetPremiumMinutes = 20 + random.nextInt(21); // 20~40분
                targetGeneralMinutes = 25 + random.nextInt(26); // 25~50분
        }

        // 필요한 인원 계산
        // 사이클 수 = (목표 시간 * 60) / 탑승 시간
        // 필요 인원 = 사이클 수 * 수용 인원
        int premiumCycles = (targetPremiumMinutes * 60) / capacity.ridingTimeSeconds;
        int generalCycles = (targetGeneralMinutes * 60) / capacity.ridingTimeSeconds;

        int premiumCount = Math.max(premiumCycles * capacity.capacityPremium, 20);
        int generalCount = Math.max(generalCycles * capacity.capacityGeneral, 40);

        addUsersToQueue(rideId, "PREMIUM", premiumCount);
        addUsersToQueue(rideId, "GENERAL", generalCount);

        // 실제 대기 시간 계산 (검증용)
        int actualPremiumMinutes = (premiumCount * capacity.ridingTimeSeconds) / (capacity.capacityPremium * 60);
        int actualGeneralMinutes = (generalCount * capacity.ridingTimeSeconds) / (capacity.capacityGeneral * 60);

        logger.info("놀이기구 {} 초기화 - 프리미엄:{}명({}분) 일반:{}명({}분) [인기도:{}]",
                rideId, premiumCount, actualPremiumMinutes, generalCount, actualGeneralMinutes,
                popularity == 3 ? "높음" : (popularity == 1 ? "낮음" : "보통"));

        return premiumCount + generalCount;
    }

    /**
     * 대기열이 부족하면 보충 - 휴일 피크 타임 기준 유지
     * 대기열이 일정 수준 이하로 떨어지면 적극적으로 보충
     */
    private int refillQueueIfNeeded(int rideId) {
        RideCapacity capacity = getRideCapacity(rideId);
        int popularity = getPopularity(rideId);
        int added = 0;

        // 목표 최소 대기 시간 (분)
        int minPremiumMinutes;
        int minGeneralMinutes;

        switch (popularity) {
            case 3: // 높은 인기 - 최소 25분 유지
                minPremiumMinutes = 25;
                minGeneralMinutes = 35;
                break;
            case 1: // 낮은 인기 - 최소 15분 유지
                minPremiumMinutes = 15;
                minGeneralMinutes = 20;
                break;
            default: // 보통 인기 - 최소 20분 유지
                minPremiumMinutes = 20;
                minGeneralMinutes = 25;
        }

        // 최소 인원 계산
        int premiumMin = Math.max((minPremiumMinutes * 60 * capacity.capacityPremium) / capacity.ridingTimeSeconds, 15);
        int generalMin = Math.max((minGeneralMinutes * 60 * capacity.capacityGeneral) / capacity.ridingTimeSeconds, 30);

        // PREMIUM 줄 확인 및 보충
        String premiumKey = QUEUE_KEY_PREFIX + rideId + ":PREMIUM";
        Long premiumSize = redisTemplate.opsForZSet().size(premiumKey);
        int currentPremium = (premiumSize != null) ? premiumSize.intValue() : 0;

        if (currentPremium < premiumMin) {
            // 부족한 만큼 + 추가 여유분 (휴일엔 계속 사람이 몰림)
            int shortage = premiumMin - currentPremium;
            int extraBuffer = 10 + random.nextInt(16); // 10~25명 추가 버퍼
            int toAdd = shortage + extraBuffer;
            addUsersToQueue(rideId, "PREMIUM", toAdd);
            added += toAdd;
            logger.info("놀이기구 {} 프리미엄 보충 - 현재:{}명 → {}명 추가 (최소:{}명 유지)",
                    rideId, currentPremium, toAdd, premiumMin);
        }

        // GENERAL 줄 확인 및 보충
        String generalKey = QUEUE_KEY_PREFIX + rideId + ":GENERAL";
        Long generalSize = redisTemplate.opsForZSet().size(generalKey);
        int currentGeneral = (generalSize != null) ? generalSize.intValue() : 0;

        if (currentGeneral < generalMin) {
            // 부족한 만큼 + 추가 여유분
            int shortage = generalMin - currentGeneral;
            int extraBuffer = 15 + random.nextInt(26); // 15~40명 추가 버퍼
            int toAdd = shortage + extraBuffer;
            addUsersToQueue(rideId, "GENERAL", toAdd);
            added += toAdd;
            logger.info("놀이기구 {} 일반 보충 - 현재:{}명 → {}명 추가 (최소:{}명 유지)",
                    rideId, currentGeneral, toAdd, generalMin);
        }

        return added;
    }

    /**
     * 대기열에 가짜 사용자 추가
     */
    private void addUsersToQueue(int rideId, String ticketType, int count) {
        String queueKey = QUEUE_KEY_PREFIX + rideId + ":" + ticketType;
        long baseTimestamp = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            long userId = userIdCounter.incrementAndGet();
            String member = "user:" + userId;
            double score = baseTimestamp + (i * 100L) + random.nextInt(100);

            redisTemplate.opsForZSet().add(queueKey, member, score);
        }
    }

    /**
     * 놀이기구 인기도 반환 (1:낮음, 2:보통, 3:높음)
     */
    private int getPopularity(int rideId) {
        // 높은 인기: 1, 5, 9, 15, 20
        if (rideId == 1 || rideId == 5 || rideId == 9 || rideId == 15 || rideId == 20) {
            return 3;
        }
        // 낮은 인기: 6, 13, 17
        if (rideId == 6 || rideId == 13 || rideId == 17) {
            return 1;
        }
        // 보통 인기: 나머지
        return 2;
    }

    /**
     * 놀이기구별 메타 정보 조회
     * (RideMetaInitializer와 동일한 데이터)
     */
    private RideCapacity getRideCapacity(int rideId) {
        return switch (rideId) {
            case 1 -> new RideCapacity(600, 5, 20);  // 10분 사이클
            case 2 -> new RideCapacity(180, 4, 16);  // 3분 사이클
            case 3 -> new RideCapacity(900, 6, 24);  // 15분 사이클
            case 4 -> new RideCapacity(180, 4, 18);
            case 5 -> new RideCapacity(240, 7, 28);
            case 6 -> new RideCapacity(360, 3, 12);
            case 7 -> new RideCapacity(120, 5, 20);
            case 8 -> new RideCapacity(180, 5, 22);
            case 9 -> new RideCapacity(180, 8, 30);
            case 10 -> new RideCapacity(300, 4, 16);
            case 11 -> new RideCapacity(180, 5, 20);
            case 12 -> new RideCapacity(240, 5, 25);
            case 13 -> new RideCapacity(120, 3, 14);
            case 14 -> new RideCapacity(180, 4, 18);
            case 15 -> new RideCapacity(1200, 6, 26);
            case 16 -> new RideCapacity(180, 6, 24);
            case 17 -> new RideCapacity(300, 4, 16);
            case 18 -> new RideCapacity(180, 5, 20);
            case 19 -> new RideCapacity(300, 4, 18);
            case 20 -> new RideCapacity(1200, 8, 30);
            default -> new RideCapacity(180, 5, 20); // 기본값
        };
    }

    /**
     * 놀이기구 수용 정보
     */
    private record RideCapacity(
            int ridingTimeSeconds,
            int capacityPremium,
            int capacityGeneral
    ) {}
}

