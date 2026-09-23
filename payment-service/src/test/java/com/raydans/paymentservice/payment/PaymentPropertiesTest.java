package com.raydans.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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

    @Test
    void negativeDeclineThresholdFailsApplicationStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues("app.payment.decline-threshold-cents=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(PaymentProperties.class)
    static class PropertiesConfig {}
}
