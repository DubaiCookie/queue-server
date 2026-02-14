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
 * 프론트엔드 테스트를 위한 가짜 대기열 데이터 생성기
 * - 서버 시작 시 각 놀이기구에 초기 대기 인원을 추가
 * - 1분마다 대기열을 확인하고 부족하면 자동으로 보충
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
     * 서버 시작 시 초기 가짜 대기열 데이터 생성
     */
    @PostConstruct
    @Async
    public void initializeMockData() {
        try {
            logger.info("=== 가짜 대기열 초기 데이터 생성 시작 ({}초 후) ===", initialDelaySeconds);
            TimeUnit.SECONDS.sleep(initialDelaySeconds);

            int totalUsers = 0;
            for (int rideId = 1; rideId <= 20; rideId++) {
                totalUsers += createInitialQueue(rideId);
            }

            logger.info("=== 가짜 대기열 초기 데이터 생성 완료 - 총 {}명 추가 ===", totalUsers);
            logger.info("=== 1분마다 자동 보충 시작 ===");

        } catch (InterruptedException e) {
            logger.error("초기 데이터 생성 중 인터럽트 발생", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("초기 데이터 생성 중 오류 발생", e);
        }
    }

    /**
     * 1분마다 대기열 확인 및 보충
     */
    @Scheduled(fixedRate = 60000, initialDelay = 70000) // 1분마다, 초기 시작 후 70초 뒤부터
    public void refillQueues() {
        try {
            int totalAdded = 0;
            for (int rideId = 1; rideId <= 20; rideId++) {
                totalAdded += refillQueueIfNeeded(rideId);
            }

            if (totalAdded > 0) {
                logger.info("대기열 자동 보충 - 총 {}명 추가", totalAdded);
            }
        } catch (Exception e) {
            logger.error("대기열 보충 중 오류 발생", e);
        }
    }

    /**
     * 특정 놀이기구의 초기 대기열 생성
     * 최소 10분 이상 대기 시간이 유지되도록 계산
     */
    private int createInitialQueue(int rideId) {
        // 놀이기구 메타 정보 기반 계산
        RideCapacity capacity = getRideCapacity(rideId);
        int popularity = getPopularity(rideId);

        // 최소 10분 대기 시간 보장 계산
        // 10분 = 600초
        // 사이클 수 = 600 / 탑승시간
        // 필요 인원 = 사이클 수 * 수용 인원
        int cyclesPerTenMinutes = 600 / capacity.ridingTimeSeconds;

        // 프리미엄과 일반의 기본 최소 인원
        int basePremiumCount = cyclesPerTenMinutes * capacity.capacityPremium;
        int baseGeneralCount = cyclesPerTenMinutes * capacity.capacityGeneral;

        // 인기도에 따른 추가 배율 (10분 + α)
        double multiplier;
        int premiumCount;
        int generalCount;

        switch (popularity) {
            case 3: // 높은 인기 - 15~25분 대기
                multiplier = 1.5 + (random.nextDouble() * 1.0); // 1.5 ~ 2.5배
                premiumCount = (int) (basePremiumCount * multiplier);
                generalCount = (int) (baseGeneralCount * multiplier);
                break;
            case 1: // 낮은 인기 - 10~15분 대기
                multiplier = 1.0 + (random.nextDouble() * 0.5); // 1.0 ~ 1.5배
                premiumCount = (int) (basePremiumCount * multiplier);
                generalCount = (int) (baseGeneralCount * multiplier);
                break;
            default: // 보통 인기 - 12~20분 대기
                multiplier = 1.2 + (random.nextDouble() * 0.8); // 1.2 ~ 2.0배
                premiumCount = (int) (basePremiumCount * multiplier);
                generalCount = (int) (baseGeneralCount * multiplier);
        }

        // 최소 인원 보장 (너무 적으면 최소값 설정)
        premiumCount = Math.max(premiumCount, 15);
        generalCount = Math.max(generalCount, 30);

        addUsersToQueue(rideId, "PREMIUM", premiumCount);
        addUsersToQueue(rideId, "GENERAL", generalCount);

        // 예상 대기 시간 계산 (로그용)
        int estimatedPremiumMinutes = (premiumCount / capacity.capacityPremium) * (capacity.ridingTimeSeconds / 60);
        int estimatedGeneralMinutes = (generalCount / capacity.capacityGeneral) * (capacity.ridingTimeSeconds / 60);

        logger.info("놀이기구 {} 초기화 - 프리미엄:{}명(약{}분) 일반:{}명(약{}분) (인기도:{})",
                rideId, premiumCount, estimatedPremiumMinutes, generalCount, estimatedGeneralMinutes, popularity);

        return premiumCount + generalCount;
    }

    /**
     * 대기열이 부족하면 보충 (최소 10분 대기 시간 유지)
     */
    private int refillQueueIfNeeded(int rideId) {
        RideCapacity capacity = getRideCapacity(rideId);
        int popularity = getPopularity(rideId);
        int added = 0;

        // 10분 대기를 위한 최소 인원 계산
        int cyclesPerTenMinutes = 600 / capacity.ridingTimeSeconds;
        int basePremiumMin = cyclesPerTenMinutes * capacity.capacityPremium;
        int baseGeneralMin = cyclesPerTenMinutes * capacity.capacityGeneral;

        // 목표 최소 인원 (이 이하로 떨어지면 보충)
        int premiumMin;
        int generalMin;

        switch (popularity) {
            case 3: // 높은 인기 - 최소 12분 유지
                premiumMin = (int) (basePremiumMin * 1.2);
                generalMin = (int) (baseGeneralMin * 1.2);
                break;
            case 1: // 낮은 인기 - 최소 10분 유지
                premiumMin = basePremiumMin;
                generalMin = baseGeneralMin;
                break;
            default: // 보통 인기 - 최소 11분 유지
                premiumMin = (int) (basePremiumMin * 1.1);
                generalMin = (int) (baseGeneralMin * 1.1);
        }

        // 최소값 보장
        premiumMin = Math.max(premiumMin, 10);
        generalMin = Math.max(generalMin, 20);

        // PREMIUM 줄 확인 및 보충
        String premiumKey = QUEUE_KEY_PREFIX + rideId + ":PREMIUM";
        Long premiumSize = redisTemplate.opsForZSet().size(premiumKey);
        int currentPremium = (premiumSize != null) ? premiumSize.intValue() : 0;

        if (currentPremium < premiumMin) {
            // 부족한 만큼 + 약간의 여유분 추가
            int shortage = premiumMin - currentPremium;
            int toAdd = shortage + (3 + random.nextInt(5)); // 부족분 + 3~7명
            addUsersToQueue(rideId, "PREMIUM", toAdd);
            added += toAdd;
            logger.debug("놀이기구 {} 프리미엄 보충 - 현재:{}명 최소:{}명 추가:{}명",
                    rideId, currentPremium, premiumMin, toAdd);
        }

        // GENERAL 줄 확인 및 보충
        String generalKey = QUEUE_KEY_PREFIX + rideId + ":GENERAL";
        Long generalSize = redisTemplate.opsForZSet().size(generalKey);
        int currentGeneral = (generalSize != null) ? generalSize.intValue() : 0;

        if (currentGeneral < generalMin) {
            // 부족한 만큼 + 약간의 여유분 추가
            int shortage = generalMin - currentGeneral;
            int toAdd = shortage + (5 + random.nextInt(10)); // 부족분 + 5~14명
            addUsersToQueue(rideId, "GENERAL", toAdd);
            added += toAdd;
            logger.debug("놀이기구 {} 일반 보충 - 현재:{}명 최소:{}명 추가:{}명",
                    rideId, currentGeneral, generalMin, toAdd);
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
            case 1 -> new RideCapacity(10, 5, 20);
            case 2 -> new RideCapacity(15, 4, 16);
            case 3 -> new RideCapacity(20, 6, 24);
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

