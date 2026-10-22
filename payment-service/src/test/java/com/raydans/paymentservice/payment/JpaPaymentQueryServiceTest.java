package com.raydans.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class JpaPaymentQueryServiceTest {

    PaymentRepository payments = mock(PaymentRepository.class);

    JpaPaymentQueryService service = new JpaPaymentQueryService(payments);

    @Test
    void theOwnersPaymentIsReturned() {
        PaymentEntity row = new PaymentEntity(101L, 99L, 44000, PaymentStatus.SUCCEEDED);
        ReflectionTestUtils.setField(row, "id", 7L);
        when(payments.findByReservationIdAndCustomerId(101L, 99L)).thenReturn(Optional.of(row));

        PaymentResponse response = service.get(101L, 99L);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.reservationId()).isEqualTo(101L);
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void aPaymentTheQueryDoesNotFindForThisCustomerIsNotFound() {
        // The repository is asked with the caller's id, so "not yours" arrives here as
        // an empty result, the same as an unissued reservation.
        when(payments.findByReservationIdAndCustomerId(101L, 98L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(101L, 98L)).isInstanceOf(PaymentNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void aNonPositiveReservationIdIsRefusedWithoutALookup(long reservationId) {
        assertThatThrownBy(() -> service.get(reservationId, 99L))
                .isInstanceOf(InvalidPaymentLookupException.class)
                .hasMessage("reservationId must be a positive id");
        verifyNoInteractions(payments);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void aNonPositiveCustomerIdIsRefusedWithoutALookup(long customerId) {
        assertThatThrownBy(() -> service.get(101L, customerId))
                .isInstanceOf(InvalidPaymentLookupException.class)
                .hasMessage("X-Customer-Id must be a positive id");
        verifyNoInteractions(payments);
    }
}
