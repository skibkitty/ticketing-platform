package com.raydans.paymentservice.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    @Query(
            value =
                    "SELECT * FROM payment.outbox_events "
                            + "WHERE published_at IS NULL ORDER BY id ASC LIMIT 20 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEventEntity> findUnpublishedBatch();
}
