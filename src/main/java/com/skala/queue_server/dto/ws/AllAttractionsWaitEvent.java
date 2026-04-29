package com.skala.queue_server.dto.ws;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class AllAttractionsWaitEvent {

    private List<AttractionWait> attractions;

    @Getter
    @AllArgsConstructor
    public static class AttractionWait {
        private Long attractionId;
        private int waitingMinutesPremium;
        private int waitingMinutesBasic;
        private int queueCountPremium;
        private int queueCountBasic;
    }
}
