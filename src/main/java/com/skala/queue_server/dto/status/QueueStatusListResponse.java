package com.skala.queue_server.dto.status;

import java.util.List;

public record QueueStatusListResponse(
        Long userId,
        List<QueueStatusItem> items
) {}

