package com.skala.queue_server.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class IssuedTicketValidationResponse {

    private Long issuedTicketId;
    private Long ownerUserId;
    private String ticketType;
    private String entryStatus;
}
