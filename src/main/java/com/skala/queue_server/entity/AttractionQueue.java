package com.skala.queue_server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "attraction_queue")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttractionQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attraction_queue_id")
    private Long attractionQueueId;

    @Column(name = "attraction_cycle_id")
    private Long attractionCycleId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "attraction_id", nullable = false)
    private Long attractionId;

    @Column(name = "issued_ticket_id", nullable = false)
    private Long issuedTicketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type", nullable = false, length = 10)
    private TicketType ticketType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QueueStatus status;


    @Column(name = "defer_count", nullable = false)
    private int deferCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = QueueStatus.WAITING;
        if (deferCount == 0) deferCount = 0;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
