package com.parknest.api.payments;

/**
 * The payment provider (not chosen yet: see the frontend's docs/payments.md). Each provider is one implementation;
 * parknest.payments.provider picks it. The browser opens the provider's checkout with the order, then the server
 * confirms the payment here, never trusting the browser alone.
 */
public interface PaymentGateway {

    /** MOCK, RAZORPAY, CASHFREE... (sent to the app as payment.provider). */
    String provider();

    Order createOrder(String receipt, long amountPaise);

    /** True when the provider confirms the order was paid by this payment. */
    boolean verify(String orderId, String paymentId, String signature);

    /** Starts a refund; returns the provider's refund id. */
    String refund(String paymentId, long amountPaise);

    record Order(String orderId, String clientToken) {}
}
