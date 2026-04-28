package com.skala.queue_server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class QueueStatusResponse {

    private Long userId;
    private List<QueueStatusItem> queues;
}
