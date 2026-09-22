package com.raydans.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class PaymentPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void negativeDeclineThresholdIsRejected() {
        PaymentProperties properties = new PaymentProperties();
        properties.setDeclineThresholdCents(-1);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void zeroDeclineThresholdIsAccepted() {
        PaymentProperties properties = new PaymentProperties();
        properties.setDeclineThresholdCents(0);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void defaultThresholdIsAccepted() {
        assertThat(validator.validate(new PaymentProperties())).isEmpty();
    }
}
