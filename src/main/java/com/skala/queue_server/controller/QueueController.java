package com.skala.queue_server.controller;

import com.skala.queue_server.dto.*;
import com.skala.queue_server.service.AttractionSchedulerService;
import com.skala.queue_server.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/queue")
@RequiredArgsConstructor
@Tag(name = "Queue API", description = "놀이기구 대기열 관리")
public class QueueController {

    private final QueueService queueService;
    private final AttractionSchedulerService schedulerService;

    @Operation(summary = "대기열 등록")
    @PostMapping("/attractions/enqueue")
    public ResponseEntity<EnqueueResponse> enqueue(@Valid @RequestBody EnqueueRequest request) {
        // TODO: JWT에서 requesterId 추출 (현재는 요청 userId 그대로 사용)
        EnqueueResponse response = queueService.enqueue(
                request.getUserId(),
                request.getAttractionId(),
                request.getIssuedTicketId(),
                request.getTicketType()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "대기열 상태 조회")
    @GetMapping("/attractions/status/{userId}")
    public ResponseEntity<QueueStatusResponse> getStatus(
            @PathVariable Long userId,
            @RequestAttribute(name = "authenticatedUserId", required = false) Long requesterId) {
        // TODO: JWT 필터 연동 후 requesterId 활용
        Long rid = requesterId != null ? requesterId : userId;
        return ResponseEntity.ok(queueService.getStatus(userId, rid));
    }

    @Operation(summary = "대기 미루기")
    @PostMapping("/attractions/defer")
    public ResponseEntity<DeferResponse> defer(@Valid @RequestBody DeferRequest request) {
        Long rid = request.getUserId(); // TODO: JWT에서 추출
        return ResponseEntity.ok(queueService.defer(request.getUserId(), request.getAttractionId(), rid));
    }

    @Operation(summary = "대기열 취소")
    @PostMapping("/attractions/cancel")
    public ResponseEntity<CancelResponse> cancel(@Valid @RequestBody CancelRequest request) {
        Long rid = request.getUserId(); // TODO: JWT에서 추출
        return ResponseEntity.ok(queueService.cancel(request.getUserId(), request.getAttractionId(), rid));
    }

    @Operation(summary = "탑승 완료")
    @PostMapping("/attractions/complete")
    public ResponseEntity<CompleteResponse> complete(@Valid @RequestBody CompleteRequest request) {
        Long rid = request.getUserId(); // TODO: JWT에서 추출
        return ResponseEntity.ok(queueService.complete(
                request.getUserId(), request.getAttractionId(), request.getRideCode(), rid));
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
