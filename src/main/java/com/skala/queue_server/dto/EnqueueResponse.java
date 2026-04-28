package com.skala.queue_server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class EnqueueResponse {

    private int position;
    private int estimatedMinutes;
}
