package com.raydans.paymentservice.payment;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    @PositiveOrZero
    private int declineThresholdCents = 50000;

    public int getDeclineThresholdCents() {
        return declineThresholdCents;
    }

    public void setDeclineThresholdCents(int declineThresholdCents) {
        this.declineThresholdCents = declineThresholdCents;
    }
}
