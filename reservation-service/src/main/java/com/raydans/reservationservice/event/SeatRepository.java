package com.raydans.reservationservice.event;

import com.raydans.reservationservice.web.SeatStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<SeatEntity, Long> {

    List<SeatEntity> findByEvent_IdOrderById(Long eventId);

    List<SeatEntity> findByEvent_IdAndStatusOrderById(Long eventId, SeatStatus status);
}