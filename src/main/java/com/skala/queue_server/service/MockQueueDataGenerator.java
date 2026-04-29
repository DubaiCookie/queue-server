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

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "queue.mock.enabled", havingValue = "true")
public class MockQueueDataGenerator {

    private static final Logger logger = LoggerFactory.getLogger(MockQueueDataGenerator.class);
    private static final String QUEUE_KEY_PREFIX = "queue:attraction:";
    private static final Random random = new Random();

    private final RedisTemplate<String, String> redisTemplate;
    private final AtomicLong userIdCounter = new AtomicLong(100000);

    @Value("${queue.mock.initial-delay-seconds:5}")
    private int initialDelaySeconds;

    @PostConstruct
    @Async
    public void initializeMockData() {
        try {
            logger.info("=== 휴일 피크 타임 대기열 초기화 시작 ({}초 후) ===", initialDelaySeconds);
            TimeUnit.SECONDS.sleep(initialDelaySeconds);

            int totalUsers = 0;
            for (int rideId = 1; rideId <= 20; rideId++) {
                totalUsers += createInitialQueue(rideId);
            }

            logger.info("=== 대기열 초기화 완료 - 총 {}명 대기 중 ===", totalUsers);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("초기 데이터 생성 중 오류 발생", e);
        }
    }

    @Scheduled(fixedRate = 30000, initialDelay = 40000)
    public void refillQueues() {
        try {
            int totalAdded = 0;
            for (int rideId = 1; rideId <= 20; rideId++) {
                totalAdded += refillQueueIfNeeded(rideId);
            }
            if (totalAdded > 0) {
                logger.info("대기열 자동 보충 완료 - 총 {}명 추가됨", totalAdded);
            }
        } catch (Exception e) {
            logger.error("대기열 보충 중 오류 발생", e);
        }
    }

    private int createInitialQueue(int rideId) {
        RideCapacity capacity = getRideCapacity(rideId);
        int popularity = getPopularity(rideId);

        int targetGeneralMinutes = switch (popularity) {
            case 3 -> 50 + random.nextInt(31);
            case 1 -> 20 + random.nextInt(16);
            default -> 30 + random.nextInt(26);
        };
        int targetPremiumMinutes = (int) (targetGeneralMinutes * (0.3 + random.nextDouble() * 0.2));

        int generalCount = (targetGeneralMinutes * 60 * capacity.capacityBasic) / capacity.ridingTimeSeconds;
        int premiumCount = (targetPremiumMinutes * 60 * capacity.capacityPremium) / capacity.ridingTimeSeconds;

        generalCount = Math.max(generalCount, 30);
        premiumCount = Math.max(premiumCount, 10);

        int actualPremiumMinutes = (premiumCount * capacity.ridingTimeSeconds) / (capacity.capacityPremium * 60);
        int actualGeneralMinutes = (generalCount * capacity.ridingTimeSeconds) / (capacity.capacityBasic * 60);

        if (actualPremiumMinutes >= actualGeneralMinutes) {
            int targetMinutes = actualGeneralMinutes / 2;
            premiumCount = (targetMinutes * 60 * capacity.capacityPremium) / capacity.ridingTimeSeconds;
            premiumCount = Math.max(premiumCount, 10);
            actualPremiumMinutes = (premiumCount * capacity.ridingTimeSeconds) / (capacity.capacityPremium * 60);
            logger.warn("놀이기구 {} 프리미엄 대기시간 조정 → {}분 (일반:{}분)", rideId, actualPremiumMinutes, actualGeneralMinutes);
        }

        addUsersToQueue(rideId, "PREMIUM", premiumCount);
        addUsersToQueue(rideId, "BASIC", generalCount);

        logger.info("놀이기구 {} 초기화 - PREMIUM:{}명({}분) BASIC:{}명({}분) [인기도:{}]",
                rideId, premiumCount, actualPremiumMinutes, generalCount, actualGeneralMinutes,
                popularity == 3 ? "높음" : (popularity == 1 ? "낮음" : "보통"));

        return premiumCount + generalCount;
    }

    private int refillQueueIfNeeded(int rideId) {
        RideCapacity capacity = getRideCapacity(rideId);
        int popularity = getPopularity(rideId);
        int added = 0;

        int minGeneralMinutes = switch (popularity) {
            case 3 -> 40;
            case 1 -> 18;
            default -> 28;
        };
        int minPremiumMinutes = (int) (minGeneralMinutes * (0.4 + random.nextDouble() * 0.2));

        int generalMin = Math.max((minGeneralMinutes * 60 * capacity.capacityBasic) / capacity.ridingTimeSeconds, 30);
        int premiumMin = Math.max((minPremiumMinutes * 60 * capacity.capacityPremium) / capacity.ridingTimeSeconds, 10);

        int calcPremiumMin = (premiumMin * capacity.ridingTimeSeconds) / (capacity.capacityPremium * 60);
        int calcGeneralMin = (generalMin * capacity.ridingTimeSeconds) / (capacity.capacityBasic * 60);
        if (calcPremiumMin >= calcGeneralMin) {
            premiumMin = Math.max(((calcGeneralMin / 2) * 60 * capacity.capacityPremium) / capacity.ridingTimeSeconds, 10);
        }

        // BASIC 보충
        String basicKey = QUEUE_KEY_PREFIX + rideId + ":BASIC";
        Long basicSize = redisTemplate.opsForZSet().size(basicKey);
        int currentBasic = basicSize != null ? basicSize.intValue() : 0;
        if (currentBasic < generalMin) {
            int toAdd = (generalMin - currentBasic) + 15 + random.nextInt(26);
            addUsersToQueue(rideId, "BASIC", toAdd);
            added += toAdd;
            logger.info("놀이기구 {} BASIC 보충 - {}명 추가", rideId, toAdd);
        }

        // PREMIUM 보충
        String premiumKey = QUEUE_KEY_PREFIX + rideId + ":PREMIUM";
        Long premiumSize = redisTemplate.opsForZSet().size(premiumKey);
        int currentPremium = premiumSize != null ? premiumSize.intValue() : 0;
        if (currentPremium < premiumMin) {
            int toAdd = (premiumMin - currentPremium) + 5 + random.nextInt(11);

            int finalPremiumMinutes = ((currentPremium + toAdd) * capacity.ridingTimeSeconds) / (capacity.capacityPremium * 60);
            int finalBasicMinutes = ((basicSize != null ? basicSize.intValue() : currentBasic) * capacity.ridingTimeSeconds) / (capacity.capacityBasic * 60);
            if (finalPremiumMinutes >= finalBasicMinutes && finalBasicMinutes > 0) {
                toAdd = Math.max((int) (toAdd * 0.5), 5);
            }

            addUsersToQueue(rideId, "PREMIUM", toAdd);
            added += toAdd;
            logger.info("놀이기구 {} PREMIUM 보충 - {}명 추가", rideId, toAdd);
        }

        return added;
    }

    private void addUsersToQueue(int rideId, String ticketType, int count) {
        String queueKey = QUEUE_KEY_PREFIX + rideId + ":" + ticketType;
        long baseTimestamp = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            long userId = userIdCounter.incrementAndGet();
            double score = baseTimestamp + (i * 100L) + random.nextInt(100);
            redisTemplate.opsForZSet().add(queueKey, String.valueOf(userId), score);
        }
    }

    private int getPopularity(int rideId) {
        if (rideId == 1 || rideId == 5 || rideId == 9 || rideId == 15 || rideId == 20) return 3;
        if (rideId == 6 || rideId == 13 || rideId == 17) return 1;
        return 2;
    }

    private RideCapacity getRideCapacity(int rideId) {
        return switch (rideId) {
            case 1  -> new RideCapacity(300,  10, 30);
            case 2  -> new RideCapacity(180,   8, 27);
            case 3  -> new RideCapacity(900,   8, 22);
            case 4  -> new RideCapacity(180,   8, 22);
            case 5  -> new RideCapacity(240,   7, 28);
            case 6  -> new RideCapacity(360,   3, 12);
            case 7  -> new RideCapacity(120,  12, 38);
            case 8  -> new RideCapacity(180,  10, 30);
            case 9  -> new RideCapacity(180,   8, 30);
            case 10 -> new RideCapacity(300,   6, 19);
            case 11 -> new RideCapacity(180,  12, 38);
            case 12 -> new RideCapacity(240,   5, 25);
            case 13 -> new RideCapacity(120,   6, 19);
            case 14 -> new RideCapacity(180,   6, 19);
            case 15 -> new RideCapacity(1200,  6, 26);
            case 16 -> new RideCapacity(180,  12, 38);
            case 17 -> new RideCapacity(300,   6, 19);
            case 18 -> new RideCapacity(180,   6, 19);
            case 19 -> new RideCapacity(300,   6, 19);
            case 20 -> new RideCapacity(1200,  8, 30);
            default -> new RideCapacity(180,   5, 20);
        };
    }

    private record RideCapacity(int ridingTimeSeconds, int capacityPremium, int capacityBasic) {}
}
