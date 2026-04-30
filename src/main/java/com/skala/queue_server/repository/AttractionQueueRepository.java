package com.skala.queue_server.repository;

import com.skala.queue_server.entity.AttractionQueue;
import com.skala.queue_server.entity.QueueStatus;
import com.skala.queue_server.entity.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AttractionQueueRepository extends JpaRepository<AttractionQueue, Long> {

    Optional<AttractionQueue> findByUserIdAndAttractionIdAndStatusIn(
            Long userId, Long attractionId, List<QueueStatus> statuses);

    List<AttractionQueue> findByUserIdAndStatusIn(Long userId, List<QueueStatus> statuses);

    @Query("SELECT DISTINCT q.userId FROM AttractionQueue q WHERE q.status IN :statuses")
    List<Long> findDistinctUserIdsByStatusIn(@Param("statuses") List<QueueStatus> statuses);

    boolean existsByUserIdAndAttractionIdAndStatusIn(
            Long userId, Long attractionId, List<QueueStatus> statuses);

    boolean existsByIssuedTicketIdAndStatusIn(Long issuedTicketId, List<QueueStatus> statuses);

    List<AttractionQueue> findByStatusAndUpdatedAtBefore(QueueStatus status, LocalDateTime before);

    List<AttractionQueue> findByStatusIn(List<QueueStatus> statuses);

    List<AttractionQueue> findByAttractionIdAndTicketTypeAndStatus(
            Long attractionId, TicketType ticketType, QueueStatus status);

    Optional<AttractionQueue> findFirstByUserIdAndAttractionIdAndTicketTypeAndStatusOrderByCreatedAtDesc(
            Long userId, Long attractionId, TicketType ticketType, QueueStatus status);

    Optional<AttractionQueue> findFirstByUserIdAndAttractionIdAndStatusOrderByUpdatedAtDesc(
            Long userId, Long attractionId, QueueStatus status);
}
