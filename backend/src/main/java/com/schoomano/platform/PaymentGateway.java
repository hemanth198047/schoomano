package com.schoomano.platform;

/**
 * The online payment provider. Only the sandbox adapter exists (local and test use, no real money). A real provider
 * (Razorpay, PayU...) needs a merchant account and keys and plugs in behind this interface; its webhook must be
 * verified exactly as {@link #verify} does here before anything is applied.
 */
public interface PaymentGateway {

    String name();

    /** True for adapters that move no real money and may offer a "complete this payment" shortcut. */
    boolean sandbox();

    /** Creates the provider's order for one payment and returns its id. */
    String createOrder(String receipt, long amountPaise);

    /** Checks the signature the provider sent with a webhook body, in constant time. */
    boolean verify(String body, String signature);

    /** Signs a body the way the provider would. Sandbox only: real providers sign on their side. */
    String sign(String body);
}
