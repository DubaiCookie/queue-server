package com.skala.queue_server.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AttractionResponse {
    private Long attractionId;
    private String attractionName;
    private Integer ridingTime;
    private Integer capacityPremium;
    private Integer capacityBasic;
}
