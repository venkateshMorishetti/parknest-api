package com.parknest.api.payments;

import com.parknest.api.common.Ids;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Pretend provider for local and testing (parknest.payments.provider=MOCK). The app's MOCK checkout returns
 * signature "mock_sig_" + orderId, which is what passes here. Never enable in production.
 */
@Component
@ConditionalOnProperty(name = "parknest.payments.provider", havingValue = "MOCK")
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public String provider() {
        return "MOCK";
    }

    @Override
    public Order createOrder(String receipt, long amountPaise) {
        return new Order(Ids.newId("order"), null);
    }

    @Override
    public boolean verify(String orderId, String paymentId, String signature) {
        return paymentId != null && !paymentId.isBlank() && ("mock_sig_" + orderId).equals(signature);
    }

    @Override
    public String refund(String paymentId, long amountPaise) {
        return Ids.newId("rfnd");
    }
}
