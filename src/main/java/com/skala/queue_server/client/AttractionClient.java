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

    /**
     * 회차 얼굴 분석을 즉시 트리거한다(fire-and-forget).
     *
     * 사용자가 "탑승 완료"를 누른 시점에 attraction-server의 분석 파이프라인을
     * 즉시 깨우기 위해 호출한다. 응답을 기다리지 않으며 실패해도 탑승 완료
     * 처리는 영향받지 않는다(스케줄러가 결국 보완 처리).
     */
    public void triggerCycleAnalysis(Long cycleId) {
        if (cycleId == null) {
            log.debug("Skip triggerCycleAnalysis: cycleId is null");
            return;
        }
        try {
            webClient.post()
                    .uri(attractionServerUrl + "/attractions/cycles/{cycleId}/trigger-analysis", cycleId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .subscribe(
                            unused -> log.info("triggered cycle analysis cycleId={}", cycleId),
                            err -> log.warn("triggerCycleAnalysis failed cycleId={}: {}", cycleId, err.getMessage())
                    );
        } catch (Exception e) {
            log.warn("triggerCycleAnalysis dispatch failed cycleId={}: {}", cycleId, e.getMessage());
        }
    }
}
