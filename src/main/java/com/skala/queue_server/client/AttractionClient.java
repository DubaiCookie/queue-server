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
     * 사용자 단위 단체사진 매칭을 즉시 트리거한다(fire-and-forget).
     *
     * 사용자가 "탑승 완료"를 누른 시점에 attraction-server를 거쳐 ai-server에
     * (userId, attractionId) 만으로 매칭 작업을 위임한다. ai-server가 photos/
     * 전체 스캔 + 얼굴 매칭 + 썸네일 생성/업로드 + attraction-server 콜백까지
     * 일괄 처리한다. 회차(cycle) 컬럼은 참조하지 않는다.
     *
     * 응답을 기다리지 않으며 실패해도 탑승 완료 처리는 영향받지 않는다.
     */
    public void requestUserPhotoMatch(Long userId, Long attractionId) {
        if (userId == null || attractionId == null) {
            log.debug("Skip requestUserPhotoMatch: userId={} attractionId={}", userId, attractionId);
            return;
        }
        try {
            webClient.post()
                    .uri(attractionServerUrl + "/attractions/users/photo-match")
                    .bodyValue(java.util.Map.of("userId", userId, "attractionId", attractionId))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .subscribe(
                            unused -> log.info("dispatched user-photo-match userId={} attractionId={}", userId, attractionId),
                            err -> log.warn("requestUserPhotoMatch failed userId={} attractionId={}: {}", userId, attractionId, err.getMessage())
                    );
        } catch (Exception e) {
            log.warn("requestUserPhotoMatch dispatch failed userId={} attractionId={}: {}", userId, attractionId, e.getMessage());
        }
    }
}
