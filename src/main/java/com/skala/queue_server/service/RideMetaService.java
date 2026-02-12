package com.skala.queue_server.service;

import com.skala.queue_server.dto.rideMeta.RideMeta;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RideMetaService {

    private static final Logger logger = LoggerFactory.getLogger(RideMetaService.class);
    private static final String META_KEY_PREFIX = "ride:meta:";

    private final RedisTemplate<String, String> redisTemplate;
    private final RideSchedulerManager schedulerManager;


    public void saveRideMeta(RideMeta meta) {
        String key = META_KEY_PREFIX + meta.rideId();

        // 메타 정보 저장
        redisTemplate.opsForHash().put(key, "ridingTimeSeconds", meta.ridingTimeSeconds().toString());
        redisTemplate.opsForHash().put(key, "capacityTotal", meta.capacityTotal().toString());
        redisTemplate.opsForHash().put(key, "capacityPremium", meta.capacityPremium().toString());
        redisTemplate.opsForHash().put(key, "capacityGeneral", meta.capacityGeneral().toString());

        logger.info("놀이기구 메타 저장 완료 - rideId={}, ridingTimeSeconds={}초, capacityTotal={}, capacityPremium={}, capacityGeneral={}",
                meta.rideId(),
                meta.ridingTimeSeconds(),
                meta.capacityTotal(),
                meta.capacityPremium(),
                meta.capacityGeneral());

        // 스케줄러 등록
        schedulerManager.registerRideScheduler(meta.rideId(),meta.ridingTimeSeconds());
    }
}
