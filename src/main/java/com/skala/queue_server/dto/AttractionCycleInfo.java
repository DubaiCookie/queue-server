package com.skala.queue_server.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AttractionCycleInfo {

    private Long attractionCycleId;
    private Integer cycleNumber;
    private String rideDate;
}
