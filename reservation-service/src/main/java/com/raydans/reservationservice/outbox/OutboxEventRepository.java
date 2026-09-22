package com.raydans.reservationservice.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    @Query(
            value =
                    "SELECT * FROM reservation.outbox_events "
                            + "WHERE published_at IS NULL ORDER BY id ASC LIMIT 20 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEventEntity> findUnpublishedBatch();
}