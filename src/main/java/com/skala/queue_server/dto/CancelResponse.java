package com.skala.queue_server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CancelResponse {

    private String message;
    private Long attractionId;
}
