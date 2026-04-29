package com.skala.queue_server.client;

import com.skala.queue_server.dto.AttractionCycleInfo;
import com.skala.queue_server.dto.AttractionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class AttractionClient {

    private final WebClient webClient;

    @Value("${service.attraction-server.url:http://attraction-server:8080}")
    private String attractionServerUrl;

    public AttractionResponse getAttraction(Long attractionId) {
        try {
            return webClient.get()
                    .uri(attractionServerUrl + "/attractions/{id}", attractionId)
                    .retrieve()
                    .bodyToMono(AttractionResponse.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch attraction {} from attraction-server: {}", attractionId, e.getMessage());
            return null;
        }
    }

    public AttractionCycleInfo getCurrentCycle(Long attractionId) {
        try {
            return webClient.get()
                    .uri(attractionServerUrl + "/attractions/{id}/cycles/current", attractionId)
                    .retrieve()
                    .bodyToMono(AttractionCycleInfo.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch current cycle for attraction {}: {}", attractionId, e.getMessage());
            return null;
        }
    }

    public AttractionCycleInfo getCycleByNumber(Long attractionId, String date, int cycleNumber) {
        try {
            return webClient.get()
                    .uri(attractionServerUrl + "/attractions/{id}/cycles/by-number?date={date}&cycleNumber={num}",
                            attractionId, date, cycleNumber)
                    .retrieve()
                    .bodyToMono(AttractionCycleInfo.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch cycle {} for attraction {}: {}", cycleNumber, attractionId, e.getMessage());
            return null;
        }
    }
}
