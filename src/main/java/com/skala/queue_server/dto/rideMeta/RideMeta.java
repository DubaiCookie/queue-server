package com.skala.queue_server.dto.rideMeta;

public record RideMeta(
        Long rideId,
        Integer ridingTimeSeconds,
        Integer capacityTotal,
        Integer capacityPremium,
        Integer capacityGeneral
) {}
