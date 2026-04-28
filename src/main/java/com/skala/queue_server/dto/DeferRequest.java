package com.skala.queue_server.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DeferRequest {

    @NotNull
    private Long userId;

    @NotNull
    private Long attractionId;
}
