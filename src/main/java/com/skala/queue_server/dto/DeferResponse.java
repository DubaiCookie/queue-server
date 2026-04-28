package com.skala.queue_server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class DeferResponse {

    private Long attractionId;
    private int newPosition;
    private int deferCount;
    private int estimatedMinutes;
}
