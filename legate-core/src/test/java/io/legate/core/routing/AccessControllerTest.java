package io.legate.core.routing;

import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.exception.ModelNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessControllerTest {

    private AccessController controller;

    @BeforeEach
    void setUp() {
        controller = new AccessController();
    }

    private VirtualKeyInfo keyInfo(List<String> allowed, List<String> denied) {
        return new VirtualKeyInfo(
            "wdn_live_test",
            "test-team",
            allowed,
            denied,
            new VirtualKeyInfo.RateLimitInfo(100, 1_000_000),
            new VirtualKeyInfo.SpendLimitInfo(new BigDecimal("10.00"), null),
            Map.of()
        );
    }

    // ── Null key (dev mode) ───────────────────────────────────────────────────

    @Test
    void checkAccess_whenNullKeyInfo_allowsAll() {
        assertThatCode(() -> controller.checkAccess(null, "gpt-4o")).doesNotThrowAnyException();
        assertThatCode(() -> controller.checkAccess(null, "claude-3-5-sonnet-20241022")).doesNotThrowAnyException();
    }

    // ── Wildcard patterns ─────────────────────────────────────────────────────

    @Test
    void checkAccess_whenWildcardAllowed_permitsAnyModel() {
        var keyInfo = keyInfo(List.of("*"), null);
        assertThatCode(() -> controller.checkAccess(keyInfo, "gpt-4o")).doesNotThrowAnyException();
        assertThatCode(() -> controller.checkAccess(keyInfo, "claude-3-5-sonnet-20241022")).doesNotThrowAnyException();
    }

    @Test
    void checkAccess_whenPrefixWildcard_matchesCorrectly() {
        var keyInfo = keyInfo(List.of("gpt-*"), null);

        assertThatCode(() -> controller.checkAccess(keyInfo, "gpt-4o")).doesNotThrowAnyException();
        assertThatCode(() -> controller.checkAccess(keyInfo, "gpt-4o-mini")).doesNotThrowAnyException();
        assertThatCode(() -> controller.checkAccess(keyInfo, "gpt-3.5-turbo")).doesNotThrowAnyException();

        assertThatThrownBy(() -> controller.checkAccess(keyInfo, "claude-3-5-sonnet"))
            .isInstanceOf(ModelNotAllowedException.class);
    }

    @Test
    void checkAccess_whenSuffixWildcard_matchesCorrectly() {
        var keyInfo = keyInfo(List.of("*-mini"), null);

        assertThatCode(() -> controller.checkAccess(keyInfo, "gpt-4o-mini")).doesNotThrowAnyException();
        assertThatThrownBy(() -> controller.checkAccess(keyInfo, "gpt-4o")).isInstanceOf(ModelNotAllowedException.class);
    }

    @Test
    void checkAccess_withExactMatch_works() {
        var keyInfo = keyInfo(List.of("gpt-4o", "claude-3-5-sonnet-20241022"), null);

        assertThatCode(() -> controller.checkAccess(keyInfo, "gpt-4o")).doesNotThrowAnyException();
        assertThatCode(() -> controller.checkAccess(keyInfo, "claude-3-5-sonnet-20241022")).doesNotThrowAnyException();
        assertThatThrownBy(() -> controller.checkAccess(keyInfo, "gpt-4o-mini")).isInstanceOf(ModelNotAllowedException.class);
    }

    // ── Deny rules ────────────────────────────────────────────────────────────

    @Test
    void checkAccess_whenModelDenied_throwsEvenIfAllowed() {
        // Deny overrides allow
        var keyInfo = keyInfo(List.of("gpt-*"), List.of("gpt-4o-mini"));

        assertThatCode(() -> controller.checkAccess(keyInfo, "gpt-4o")).doesNotThrowAnyException();
        assertThatThrownBy(() -> controller.checkAccess(keyInfo, "gpt-4o-mini"))
            .isInstanceOf(ModelNotAllowedException.class);
    }

    @Test
    void checkAccess_whenWildcardDenied_blocksAll() {
        var keyInfo = keyInfo(List.of("*"), List.of("*"));

        assertThatThrownBy(() -> controller.checkAccess(keyInfo, "gpt-4o"))
            .isInstanceOf(ModelNotAllowedException.class);
    }

    @Test
    void checkAccess_whenDenyWildcard_deniesMatchingModels() {
        var keyInfo = keyInfo(List.of("*"), List.of("claude-*"));

        assertThatCode(() -> controller.checkAccess(keyInfo, "gpt-4o")).doesNotThrowAnyException();
        assertThatThrownBy(() -> controller.checkAccess(keyInfo, "claude-3-5-sonnet-20241022"))
            .isInstanceOf(ModelNotAllowedException.class);
    }

    // ── Empty allowed list ─────────────────────────────────────────────────────

    @Test
    void checkAccess_whenAllowedListEmpty_deniesAll() {
        var keyInfo = keyInfo(List.of(), null);

        assertThatThrownBy(() -> controller.checkAccess(keyInfo, "gpt-4o"))
            .isInstanceOf(ModelNotAllowedException.class);
    }

    @Test
    void checkAccess_whenAllowedListNull_deniesAll() {
        var keyInfo = keyInfo(null, null);

        assertThatThrownBy(() -> controller.checkAccess(keyInfo, "gpt-4o"))
            .isInstanceOf(ModelNotAllowedException.class);
    }

    // ── Default deny ───────────────────────────────────────────────────────────

    @Test
    void checkAccess_whenModelNotInAnyList_denies() {
        var keyInfo = keyInfo(List.of("gpt-*", "claude-*"), null);

        assertThatThrownBy(() -> controller.checkAccess(keyInfo, "llama-3-70b"))
            .isInstanceOf(ModelNotAllowedException.class);
    }

    // ── Exception details ─────────────────────────────────────────────────────

    @Test
    void checkAccess_exceptionContainsModelAndKeyId() {
        var keyInfo = keyInfo(List.of("gpt-*"), null);

        assertThatThrownBy(() -> controller.checkAccess(keyInfo, "claude-3-5-sonnet-20241022"))
            .isInstanceOf(ModelNotAllowedException.class)
            .hasMessageContaining("claude-3-5-sonnet-20241022");
    }
}
