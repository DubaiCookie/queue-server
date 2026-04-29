package com.skala.queue_server.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RideMetaInitializer {

    private final AttractionSchedulerService schedulerService;

    @PostConstruct
    public void initializeRideMeta() {
        save(1L,  300,  10, 30);
        save(2L,  180,   8, 27);
        save(3L,  900,   8, 22);
        save(4L,  180,   8, 22);
        save(5L,  240,   7, 28);
        save(6L,  360,   3, 12);
        save(7L,  120,  12, 38);
        save(8L,  180,  10, 30);
        save(9L,  180,   8, 30);
        save(10L, 300,   6, 19);
        save(11L, 180,  12, 38);
        save(12L, 240,   5, 25);
        save(13L, 120,   6, 19);
        save(14L, 180,   6, 19);
        save(15L, 1200,  6, 26);
        save(16L, 180,  12, 38);
        save(17L, 300,   6, 19);
        save(18L, 180,   6, 19);
        save(19L, 300,   6, 19);
        save(20L, 1200,  8, 30);
    }

    private void save(Long attractionId, int cyclingTimeSeconds, int capacityPremium, int capacityBasic) {
        schedulerService.registerAttractionMeta(attractionId, cyclingTimeSeconds, capacityPremium, capacityBasic);
    }
}
