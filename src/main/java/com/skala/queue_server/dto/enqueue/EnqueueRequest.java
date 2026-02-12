package com.skala.queue_server.dto.enqueue;

import jakarta.validation.constraints.NotNull;

public record EnqueueRequest(
        @NotNull Long userId,
        @NotNull Long rideId,
        @NotNull String ticketType   // GENERAL | PREMIUM
) {}
