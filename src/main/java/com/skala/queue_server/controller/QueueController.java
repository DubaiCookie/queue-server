package com.skala.queue_server.controller;

import com.skala.queue_server.dto.*;
import com.skala.queue_server.service.AttractionSchedulerService;
import com.skala.queue_server.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/queue")
@RequiredArgsConstructor
@Tag(name = "Queue API", description = "놀이기구 대기열 관리")
public class QueueController {

    private final QueueService queueService;
    private final AttractionSchedulerService schedulerService;

    @Operation(summary = "대기열 등록")
    @PostMapping("/attractions/enqueue")
    public ResponseEntity<EnqueueResponse> enqueue(
            @Valid @RequestBody EnqueueRequest request,
            HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("authenticatedUserId");
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(queueService.enqueue(
                userId, request.getAttractionId(), request.getIssuedTicketId()));
    }

    @Operation(summary = "대기열 상태 조회")
    @GetMapping("/attractions/status")
    public ResponseEntity<QueueStatusResponse> getStatus(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("authenticatedUserId");
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(queueService.getStatus(userId, userId));
    }

    @Operation(summary = "대기 미루기")
    @PostMapping("/attractions/defer")
    public ResponseEntity<DeferResponse> defer(
            @Valid @RequestBody DeferRequest request,
            HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("authenticatedUserId");
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(queueService.defer(userId, request.getAttractionId(), userId));
    }

    @Operation(summary = "대기열 취소")
    @PostMapping("/attractions/cancel")
    public ResponseEntity<CancelResponse> cancel(
            @Valid @RequestBody CancelRequest request,
            HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("authenticatedUserId");
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(queueService.cancel(userId, request.getAttractionId(), userId));
    }

    @Operation(summary = "탑승 완료")
    @PostMapping("/attractions/complete")
    public ResponseEntity<CompleteResponse> complete(
            @Valid @RequestBody CompleteRequest request,
            HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("authenticatedUserId");
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(queueService.complete(
                userId, request.getAttractionId(), userId));
    }

    @Operation(summary = "[Internal] 놀이기구 대기 정보 조회 (attraction-server용)")
    @GetMapping("/attractions/{attractionId}/waiting-info")
    public ResponseEntity<?> getWaitingInfo(@PathVariable Long attractionId) {
        try {
            return ResponseEntity.ok(queueService.getWaitingInfo(attractionId));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "attractionId", attractionId,
                    "waitingMinutesPremium", 0,
                    "waitingMinutesBasic", 0,
                    "queueCountPremium", 0,
                    "queueCountBasic", 0
            ));
        }
    }

    // ── 관리용: 놀이기구 메타 등록 ──────────────────────────────────────────
    @Operation(summary = "[Admin] 놀이기구 메타 등록 (스케줄러 활성화)")
    @PostMapping("/attractions/admin/meta")
    public ResponseEntity<String> registerMeta(
            @RequestParam Long attractionId,
            @RequestParam int cyclingTimeSeconds,
            @RequestParam int capacityPremium,
            @RequestParam int capacityBasic) {
        schedulerService.registerAttractionMeta(attractionId, cyclingTimeSeconds, capacityPremium, capacityBasic);
        return ResponseEntity.ok("등록 완료");
    }
}
