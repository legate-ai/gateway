package io.legate.core.guard.builtin;

import io.legate.core.config.guard.PiiDetectorConfig;
import io.legate.core.config.guard.PiiPattern;
import io.legate.core.guard.GuardContext;
import io.legate.core.guard.GuardDecision;
import io.legate.core.guard.RequestGuard;
import io.legate.core.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Built-in guard that detects (and optionally redacts) personally-identifiable
 * information (PII) in request messages.
 *
 * <p>Supported built-in patterns: email, phone, SSN, credit card number.</p>
 *
 * <p>When the configured {@link io.legate.core.config.guard.PiiAction} is:</p>
 * <ul>
 *   <li>{@code BLOCK}  — returns {@link GuardDecision.Block} immediately.</li>
 *   <li>{@code REDACT} — replaces matches with type-labelled placeholders and returns
 *       {@link GuardDecision.Modify} with the sanitised request.</li>
 *   <li>{@code WARN}   — returns {@link GuardDecision.Warn}; request passes through unchanged.</li>
 * </ul>
 *
 * <p>Default execution order: 100.</p>
 */
public class PiiDetectorGuard implements RequestGuard {

    private static final String GUARD_NAME = "pii-detector";

    // Pre-compiled built-in patterns
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+?1[\\s.\\-]?)?(?:\\(?\\d{3}\\)?[\\s.\\-]?)\\d{3}[\\s.\\-]?\\d{4}"
    );
    private static final Pattern SSN_PATTERN = Pattern.compile(
            "\\b(?!000|666|9\\d{2})\\d{3}[\\s\\-]?(?!00)\\d{2}[\\s\\-]?(?!0000)\\d{4}\\b"
    );
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
            "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|" +
                    "6(?:011|5[0-9]{2})[0-9]{12})[\\s\\-]?\\b"
    );

    private final PiiDetectorConfig config;
    private final int order;
    private final List<PiiPatternEntry> activePatterns;

    /**
     * Creates a PII detector with the given configuration and execution order.
     */
    public PiiDetectorGuard(PiiDetectorConfig config, int order) {
        this.config = config != null ? config : PiiDetectorConfig.defaults();
        this.order = order;
        this.activePatterns = buildPatterns(this.config);
    }

    @Override
    public String getName() {
        return GUARD_NAME;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public GuardDecision inspect(GuardContext context) {
        if (context.request().messages() == null || context.request().messages().isEmpty()) {
            return new GuardDecision.Allow(GUARD_NAME);
        }

        // Build combined text for detection
        StringBuilder combined = new StringBuilder();
        for (Message msg : context.request().messages()) {
            if (msg.content() != null) {
                combined.append(msg.content()).append('\n');
            }
        }
        String text = combined.toString();

        List<String> foundTypes = detectPiiTypes(text);
        if (foundTypes.isEmpty()) {
            return new GuardDecision.Allow(GUARD_NAME);
        }

        String description = "PII detected (" + String.join(", ", foundTypes) + ")";
        return switch (config.action()) {
            case BLOCK -> new GuardDecision.Block(GUARD_NAME, description);
            case REDACT -> {
                var modified = redactMessages(context.request().messages());
                yield new GuardDecision.Modify(
                        GUARD_NAME,
                        context.request().withMessages(modified),
                        description + " — redacted"
                );
            }
            case WARN -> new GuardDecision.Warn(GUARD_NAME, description);
        };
    }

    // -------------------------------------------------------------------------

    private List<String> detectPiiTypes(String text) {
        List<String> found = new ArrayList<>();
        for (PiiPatternEntry piiPatternEntry : activePatterns) {
            if (piiPatternEntry.pattern().matcher(text).find()) {
                found.add(piiPatternEntry.label());
            }
        }
        return found;
    }

    private List<Message> redactMessages(List<Message> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg.content() == null) {
                result.add(msg);
                continue;
            }
            String redacted = msg.content();
            for (PiiPatternEntry e : activePatterns) {
                redacted = e.pattern().matcher(redacted).replaceAll(e.placeholder());
            }
            result.add(new Message(msg.role(), redacted, msg.name(), msg.toolCalls(), msg.toolCallId()));
        }
        return result;
    }

    private static List<PiiPatternEntry> buildPatterns(PiiDetectorConfig config) {
        List<PiiPatternEntry> patterns = new ArrayList<>();
        List<PiiPattern> piiPatterns = config.patterns() != null
                ? config.patterns() : List.of(PiiPattern.values());

        for (PiiPattern p : piiPatterns) {
            patterns.add(switch (p) {
                case EMAIL -> new PiiPatternEntry(EMAIL_PATTERN, "email", "[EMAIL]");
                case PHONE -> new PiiPatternEntry(PHONE_PATTERN, "phone", "[PHONE]");
                case SSN -> new PiiPatternEntry(SSN_PATTERN, "ssn", "[SSN]");
                case CREDIT_CARD -> new PiiPatternEntry(CREDIT_CARD_PATTERN, "credit-card", "[CREDIT_CARD]");
            });
        }
        for (String custom : config.customPatterns()) {
            patterns.add(new PiiPatternEntry(Pattern.compile(custom), "custom", "[REDACTED]"));
        }
        return patterns;
    }

    private record PiiPatternEntry(Pattern pattern, String label, String placeholder) {
    }
}
