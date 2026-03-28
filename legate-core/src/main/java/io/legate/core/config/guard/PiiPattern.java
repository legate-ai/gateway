package io.legate.core.config.guard;

/**
 * Built-in PII detection patterns available in the {@code PiiDetectorGuard}.
 *
 * <p>These patterns use pre-compiled regexes tuned for high recall over precision.
 * Add {@link PiiDetectorConfig#customPatterns()} for domain-specific patterns.</p>
 *
 * <p>YAML usage:</p>
 * <pre>{@code
 * legate:
 *   guards:
 *     request-guards:
 *       - type: pii-detector
 *         pii:
 *           patterns:
 *             - email
 *             - phone
 *             - ssn
 *             - credit-card
 * }</pre>
 */
public enum PiiPattern {

    /**
     * RFC 5322 email addresses.
     * Example: {@code user@example.com}
     */
    EMAIL,

    /**
     * US and international phone numbers in common formats.
     * Examples: {@code (555) 123-4567}, {@code +1-555-123-4567}, {@code 5551234567}
     */
    PHONE,

    /**
     * US Social Security Numbers.
     * Example: {@code 123-45-6789} or {@code 123456789}
     */
    SSN,

    /**
     * Major credit card numbers (Visa, Mastercard, Amex, Discover).
     * Validated with the Luhn algorithm to reduce false positives.
     * Example: {@code 4111 1111 1111 1111}
     */
    CREDIT_CARD
}
