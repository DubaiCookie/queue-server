package com.skala.queue_server.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CancelRequest {

    @NotNull
    private Long attractionId;
}
