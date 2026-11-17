package com.parknest.api.payments;

import com.parknest.api.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Used until a real provider is added (parknest.payments.provider=NONE): paying answers 503. */
@Component
@ConditionalOnProperty(name = "parknest.payments.provider", havingValue = "NONE", matchIfMissing = true)
public class NoPaymentGateway implements PaymentGateway {

    @Override
    public String provider() {
        return "NONE";
    }

    @Override
    public Order createOrder(String receipt, long amountPaise) {
        throw unavailable();
    }

    @Override
    public boolean verify(String orderId, String paymentId, String signature) {
        throw unavailable();
    }

    @Override
    public String refund(String paymentId, long amountPaise) {
        throw unavailable();
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Payments are not set up", "PAYMENTS_NOT_CONFIGURED", null,
                "Online payments are not available yet. Try again later.");
    }
}
