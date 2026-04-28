package com.skala.queue_server.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CompleteRequest {

    @NotNull
    private Long userId;

    @NotNull
    private Long attractionId;

    @NotNull
    private String rideCode;
}
