package com.raydans.reservationservice.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    List<OutboxEventEntity> findFirst20ByPublishedAtIsNullOrderByIdAsc();
}