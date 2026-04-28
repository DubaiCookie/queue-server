package com.skala.queue_server.exception;

import lombok.Getter;

@Getter
public class QueueException extends RuntimeException {

    private final ErrorCode errorCode;

    public QueueException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
