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
     */
    private int createInitialQueue(int rideId) {
        int popularity = getPopularity(rideId);

        // 인기도에 따른 초기 인원 설정
        int premiumCount;
        int generalCount;

        switch (popularity) {
            case 3: // 높은 인기
                premiumCount = 30 + random.nextInt(21); // 30~50명
                generalCount = 150 + random.nextInt(101); // 150~250명
                break;
            case 1: // 낮은 인기
                premiumCount = 10 + random.nextInt(11); // 10~20명
                generalCount = 20 + random.nextInt(31); // 20~50명
                break;
            default: // 보통 인기
                premiumCount = 15 + random.nextInt(16); // 15~30명
                generalCount = 50 + random.nextInt(51); // 50~100명
        }

        addUsersToQueue(rideId, "PREMIUM", premiumCount);
        addUsersToQueue(rideId, "GENERAL", generalCount);

        logger.info("놀이기구 {} 초기화 - 프리미엄:{}명 일반:{}명 (인기도:{})",
                rideId, premiumCount, generalCount, popularity);

        return premiumCount + generalCount;
    }

    /**
     * 대기열이 부족하면 보충
     */
    private int refillQueueIfNeeded(int rideId) {
        int popularity = getPopularity(rideId);
        int added = 0;

        // 목표 최소 인원 (이 이하로 떨어지면 보충)
        int premiumMin;
        int generalMin;

        switch (popularity) {
            case 3: // 높은 인기
                premiumMin = 20;
                generalMin = 100;
                break;
            case 1: // 낮은 인기
                premiumMin = 5;
                generalMin = 15;
                break;
            default: // 보통 인기
                premiumMin = 10;
                generalMin = 30;
        }

        // PREMIUM 줄 확인 및 보충
        String premiumKey = QUEUE_KEY_PREFIX + rideId + ":PREMIUM";
        Long premiumSize = redisTemplate.opsForZSet().size(premiumKey);
        int currentPremium = (premiumSize != null) ? premiumSize.intValue() : 0;

        if (currentPremium < premiumMin) {
            int toAdd = 5 + random.nextInt(6); // 5~10명 추가
            addUsersToQueue(rideId, "PREMIUM", toAdd);
            added += toAdd;
            logger.debug("놀이기구 {} 프리미엄 보충 - 현재:{}명 추가:{}명", rideId, currentPremium, toAdd);
        }

        // GENERAL 줄 확인 및 보충
        String generalKey = QUEUE_KEY_PREFIX + rideId + ":GENERAL";
        Long generalSize = redisTemplate.opsForZSet().size(generalKey);
        int currentGeneral = (generalSize != null) ? generalSize.intValue() : 0;

        if (currentGeneral < generalMin) {
            int toAdd = 10 + random.nextInt(16); // 10~25명 추가
            addUsersToQueue(rideId, "GENERAL", toAdd);
            added += toAdd;
            logger.debug("놀이기구 {} 일반 보충 - 현재:{}명 추가:{}명", rideId, currentGeneral, toAdd);
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
}

