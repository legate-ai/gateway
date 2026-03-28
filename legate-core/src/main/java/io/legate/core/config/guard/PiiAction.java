package io.legate.core.config.guard;

/**
 * Action taken by the PII detector guard when personally-identifiable information
 * is found in a request.
 *
 * <p>YAML usage:</p>
 * <pre>{@code
 * legate:
 *   guards:
 *     request-guards:
 *       - type: pii-detector
 *         pii:
 *           action: redact    # or REDACT, BLOCK, WARN
 * }</pre>
 */
public enum PiiAction {

    /**
     * Reject the request immediately and return HTTP 403 to the client.
     * The upstream provider never sees the request.
     */
    BLOCK,

    /**
     * Replace all detected PII with type-labelled placeholders before
     * forwarding the request.
     * <ul>
     *   <li>Email addresses → {@code [EMAIL]}</li>
     *   <li>Phone numbers → {@code [PHONE]}</li>
     *   <li>SSNs → {@code [SSN]}</li>
     *   <li>Credit card numbers → {@code [CREDIT_CARD]}</li>
     *   <li>Custom pattern matches → {@code [REDACTED]}</li>
     * </ul>
     * A {@code GuardDecision.Modify} event is emitted with the substitution details.
     */
    REDACT,

    /**
     * Allow the request to proceed unmodified but emit a {@code GuardDecisionEvent}
     * for audit purposes.
     * <p>Use this during testing to understand what PII is present in traffic
     * before switching to {@code REDACT} or {@code BLOCK}.</p>
     */
    WARN
}
