package com.skala.queue_server.repository;

import com.skala.queue_server.entity.AttractionQueue;
import com.skala.queue_server.entity.QueueStatus;
import com.skala.queue_server.entity.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AttractionQueueRepository extends JpaRepository<AttractionQueue, Long> {

    Optional<AttractionQueue> findByUserIdAndAttractionIdAndStatusIn(
            Long userId, Long attractionId, List<QueueStatus> statuses);

    List<AttractionQueue> findByUserIdAndStatusIn(Long userId, List<QueueStatus> statuses);

    boolean existsByUserIdAndAttractionIdAndStatusIn(
            Long userId, Long attractionId, List<QueueStatus> statuses);

    boolean existsByIssuedTicketIdAndStatusIn(Long issuedTicketId, List<QueueStatus> statuses);

    List<AttractionQueue> findByStatusAndUpdatedAtBefore(QueueStatus status, LocalDateTime before);

    List<AttractionQueue> findByAttractionIdAndTicketTypeAndStatus(
            Long attractionId, TicketType ticketType, QueueStatus status);
}
