package com.raydans.reservationservice.reservation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<ReservationEntity, Long> {

    List<ReservationEntity> findByCustomerIdOrderByIdDesc(long customerId);
}