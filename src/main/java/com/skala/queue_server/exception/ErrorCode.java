package com.skala.queue_server.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    MISSING_REQUIRED_FIELD(HttpStatus.BAD_REQUEST,       "필수 입력값이 누락되었습니다."),
    INVALID_TICKET_TYPE(HttpStatus.BAD_REQUEST,          "티켓 종류는 BASIC, PREMIUM 중 하나여야 합니다."),
    MISSING_TOKEN(HttpStatus.UNAUTHORIZED,               "액세스 토큰이 없습니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED,               "액세스 토큰이 만료되었습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN,                      "본인의 대기열만 조회할 수 있습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND,                 "존재하지 않는 사용자입니다."),
    ATTRACTION_NOT_FOUND(HttpStatus.NOT_FOUND,           "존재하지 않는 놀이기구입니다."),
    ISSUED_TICKET_NOT_FOUND(HttpStatus.NOT_FOUND,        "유효한 티켓이 없습니다."),
    QUEUE_NOT_FOUND(HttpStatus.NOT_FOUND,                "대기열에 등록되어 있지 않습니다."),
    ALREADY_IN_QUEUE(HttpStatus.CONFLICT,                "이미 해당 놀이기구 대기열에 등록되어 있습니다."),
    ATTRACTION_NOT_ACTIVE(HttpStatus.CONFLICT,           "현재 운영 중이지 않는 놀이기구입니다."),
    TICKET_ALREADY_USED(HttpStatus.CONFLICT,             "이미 사용된 티켓입니다."),
    DEFER_LIMIT_EXCEEDED(HttpStatus.CONFLICT,            "미루기 횟수를 초과했습니다."),
    QUEUE_STATUS_NOT_AVAILABLE(HttpStatus.CONFLICT,      "현재 미루기가 불가능한 상태입니다."),
    QUEUE_ALREADY_COMPLETED(HttpStatus.CONFLICT,         "이미 완료된 대기열입니다."),
    INVALID_RIDE_CODE(HttpStatus.CONFLICT,               "탑승 코드가 올바르지 않습니다.");

    private final HttpStatus httpStatus;
    private final String message;
}
