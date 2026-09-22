package com.raydans.paymentservice.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    private int declineThresholdCents = 50000;

    public int getDeclineThresholdCents() {
        return declineThresholdCents;
    }

    public void setDeclineThresholdCents(int declineThresholdCents) {
        this.declineThresholdCents = declineThresholdCents;
    }
}
