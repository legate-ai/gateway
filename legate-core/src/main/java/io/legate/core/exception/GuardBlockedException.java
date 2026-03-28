package io.legate.core.exception;

/**
 * Thrown when a guard blocks a request.
 */
public class GuardBlockedException extends LegateException {
    private final String guardName;
    private final String reason;

    public GuardBlockedException(String guardName, String reason) {
        super(String.format("Request blocked by guard '%s': %s", guardName, reason),
                "GUARD_BLOCKED");
        this.guardName = guardName;
        this.reason = reason;
    }

    public String getGuardName() {
        return guardName;
    }

    public String getReason() {
        return reason;
    }
}
