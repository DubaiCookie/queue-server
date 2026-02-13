package com.skala.queue_server.controller;

import com.skala.queue_server.dto.enqueue.EnqueueRequest;
import com.skala.queue_server.dto.enqueue.EnqueueResponse;
import com.skala.queue_server.dto.ride.RideQueueInfoListResponse;
import com.skala.queue_server.dto.status.QueueStatusListResponse;
import com.skala.queue_server.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
@Tag(
        name = "Queue API",
        description = "대기열 서버 API (메인 서버 전용)"
)
public class QueueController {

    private static final Logger logger = LoggerFactory.getLogger(QueueController.class);
    private final QueueService queueService;

    @Operation(
            summary = "대기열 등록",
            description = "메인 서버에서 호출하는 대기열 등록 API입니다. 사용자/놀이기구/티켓타입 정보를 기반으로 Redis 대기열에 등록하고 현재 순번과 예상 대기 시간을 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "대기열 등록 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EnqueueResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (유효성 검사 실패)"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 내부 오류"
            )
    })
    @PostMapping("/enqueue")
    public EnqueueResponse enqueue(@RequestBody @Valid EnqueueRequest request) {
        logger.info("대기열 등록 요청 - 사용자={} 놀이기구={} 티켓타입={}", request.userId(), request.rideId(), request.ticketType());

        EnqueueResponse response = queueService.enqueue(
                request.userId(),
                request.rideId(),
                request.ticketType()
        );

        logger.info("대기열 등록 응답 - 현재순번={} 예상대기시간={}분", response.position(), response.estimatedWaitMinutes());
        return response;
    }

    @Operation(
            summary = "사용자 모든 대기열 상태 조회",
            description = "사용자가 대기 중인 모든 놀이기구의 현재 순번과 예상 대기 시간을 리스트로 반환합니다. 최대 3개까지만 유지됩니다."
    )
    @GetMapping("/status/all")
    public QueueStatusListResponse getAllStatus(@RequestParam("userId") Long userId) {
        logger.info("사용자 전체 대기열 상태 조회 요청 - 사용자={}", userId);
        QueueStatusListResponse response = queueService.getAllStatus(userId);
        logger.info("사용자 전체 대기열 상태 조회 응답 - 항목수={}", response.items().size());
        return response;
    }

    @Operation(
            summary = "모든 놀이기구 대기열 정보 조회",
            description = "모든 놀이기구의 프리미엄/일반 대기열의 대기 인원과 예상 대기 시간을 리스트로 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = RideQueueInfoListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 내부 오류"
            )
    })
    @GetMapping("/rides/info")
    public RideQueueInfoListResponse getAllRidesQueueInfo() {
        logger.info("모든 놀이기구 대기열 정보 조회 요청");
        RideQueueInfoListResponse response = queueService.getAllRidesQueueInfo();
        logger.info("모든 놀이기구 대기열 정보 조회 응답 - 놀이기구수={}", response.rides().size());
        return response;
    }

    @Operation(
            summary = "특정 놀이기구 대기열 정보 조회",
            description = "특정 놀이기구의 프리미엄/일반 대기열의 대기 인원과 예상 대기 시간을 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = com.skala.queue_server.dto.ride.RideQueueInfoDto.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (존재하지 않는 놀이기구)"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 내부 오류"
            )
    })
    @GetMapping("/rides/{rideId}/info")
    public com.skala.queue_server.dto.ride.RideQueueInfoDto getRideQueueInfo(@PathVariable("rideId") Long rideId) {
        logger.info("놀이기구 대기열 정보 조회 요청 - 놀이기구={}", rideId);
        com.skala.queue_server.dto.ride.RideQueueInfoDto response = queueService.getRideQueueInfo(rideId);
        logger.info("놀이기구 대기열 정보 조회 응답 - 놀이기구={} 대기열타입수={}", rideId, response.waitTimes().size());
        return response;
    }
}
