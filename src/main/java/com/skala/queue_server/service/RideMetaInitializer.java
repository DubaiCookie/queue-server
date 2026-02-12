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
        // 놀이기구 20개 고정 등록
        // 1,2,3만 임시 변경
        save(1L, 10, 25, 5, 20);
        save(2L, 15, 20, 4, 16);
        save(3L, 20, 30, 6, 24);
        save(4L, 180, 22, 4, 18);
        save(5L, 240, 35, 7, 28);
        save(6L, 360, 15, 3, 12);
        save(7L, 120, 25, 5, 20);
        save(8L, 180, 27, 5, 22);
        save(9L, 180, 38, 8, 30);
        save(10L, 300, 20, 4, 16);

        save(11L, 180, 25, 5, 20);
        save(12L, 240, 30, 5, 25);
        save(13L, 120, 17, 3, 14);
        save(14L, 180, 22, 4, 18);
        save(15L, 1200, 32, 6, 26);
        save(16L, 180, 30, 6, 24);
        save(17L, 300, 20, 4, 16);
        save(18L, 180, 25, 5, 20);
        save(19L, 300, 22, 4, 18);
        save(20L, 1200, 38, 8, 30);
    }

    private void save(Long rideId, int ridingTimeSeconds, int capacityTotal, int capacityPremium, int capacityGeneral) {
        RideMeta meta = new RideMeta(rideId, ridingTimeSeconds, capacityTotal, capacityPremium, capacityGeneral);
        rideMetaService.saveRideMeta(meta);
    }
}