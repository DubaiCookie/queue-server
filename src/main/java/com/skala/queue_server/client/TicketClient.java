package com.skala.queue_server.client;

import com.skala.queue_server.dto.IssuedTicketValidationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketClient {

    private final WebClient webClient;

    @Value("${service.ticket-server.url:http://ticket-server:8080}")
    private String ticketServerUrl;

    public IssuedTicketValidationResponse getIssuedTicket(Long issuedTicketId) {
        try {
            return webClient.get()
                    .uri(ticketServerUrl + "/tickets/issued/internal/{id}", issuedTicketId)
                    .retrieve()
                    .bodyToMono(IssuedTicketValidationResponse.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch issuedTicket {} from ticket-server: {}", issuedTicketId, e.getMessage());
            return null;
        }
    }
}
