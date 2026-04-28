package com.skala.queue_server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class QueueStatusItem {

    private Long attractionId;
    private String attractionName;
    private String ticketType;
    private int position;
    private int estimatedMinutes;
}
