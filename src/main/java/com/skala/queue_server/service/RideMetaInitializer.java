package com.skala.queue_server.service;

import com.skala.queue_server.dto.rideMeta.RideMeta;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RideMetaInitializer {

    private final RideMetaService rideMetaService;

    @PostConstruct
    public void initializeRideMeta() {
        // 놀이기구 10개 고정 등록
        save(1L, 10, 10, 5, 5);
        save(2L, 20, 8, 4, 4);
        save(3L, 25, 12, 6, 6);
        save(4L, 27, 6, 3, 3);
        save(5L, 30, 10, 5, 5);
        save(6L, 40, 14, 7, 7);
        save(7L, 45, 6, 3, 3);
        save(8L, 60, 16, 8, 8);
        save(9L, 80, 8, 4, 4);
        save(10L, 120, 20, 10, 10);
    }

    private void save(Long rideId, int ridingTimeSeconds, int capacityTotal, int capacityPremium, int capacityGeneral) {
        RideMeta meta = new RideMeta(rideId, ridingTimeSeconds, capacityTotal, capacityPremium, capacityGeneral);
        rideMetaService.saveRideMeta(meta);
    }
}