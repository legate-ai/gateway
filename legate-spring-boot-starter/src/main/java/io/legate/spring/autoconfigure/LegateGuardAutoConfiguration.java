package io.legate.spring.autoconfigure;

import io.legate.core.config.LegateConfig;
import io.legate.core.config.guard.BuiltInRequestGuardType;
import io.legate.core.config.guard.RequestGuardConfig;
import io.legate.core.guard.GuardPipeline;
import io.legate.core.guard.RequestGuard;
import io.legate.core.guard.ResponseGuard;
import io.legate.core.guard.builtin.KeywordBlockerGuard;
import io.legate.core.guard.builtin.MaxTokensLimiterGuard;
import io.legate.core.guard.builtin.PiiDetectorGuard;
import io.legate.core.guard.builtin.SystemPromptInjectorGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto-configuration that builds a {@link GuardPipeline} from the {@code legate.guards}
 * configuration block.
 *
 * <p>Built-in guard types ({@code pii-detector}, {@code keyword-blocker},
 * {@code max-tokens}, {@code system-prompt-injector}) are instantiated automatically.
 * Custom guard types (fully-qualified class names) are ignored in this implementation
 * — they can be registered by providing a custom {@link GuardPipeline} bean.</p>
 */
@AutoConfiguration
public class LegateGuardAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LegateGuardAutoConfiguration.class);

    /**
     * Builds the {@link GuardPipeline} from configured guards.
     *
     * <p>If no guards are configured, an empty no-op pipeline is returned.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public GuardPipeline legateGuardPipeline(LegateConfig legateConfig) {
        if (legateConfig.guards() == null || !legateConfig.guards().hasGuards()) {
            log.debug("No guards configured — using empty no-op pipeline");
            return new GuardPipeline();
        }

        List<RequestGuard> requestGuards = buildRequestGuards(legateConfig);
        List<ResponseGuard> responseGuards = List.of(); // Phase 3 response guards are empty by default

        log.info("GuardPipeline: {} request guard(s) configured", requestGuards.size());
        return new GuardPipeline(requestGuards, responseGuards);
    }

    // -------------------------------------------------------------------------

    private List<RequestGuard> buildRequestGuards(LegateConfig config) {
        List<RequestGuard> guards = new ArrayList<>();

        for (RequestGuardConfig gc : config.guards().requestGuards()) {
            if (!gc.enabled()) continue;

            BuiltInRequestGuardType builtIn = BuiltInRequestGuardType.fromConfigKey(gc.type());
            if (builtIn == null) {
                log.warn("Custom guard type '{}' not instantiated — provide a custom GuardPipeline bean", gc.type());
                continue;
            }

            int order = gc.order() > 0 ? gc.order() : builtIn.defaultOrder();
            RequestGuard guard = switch (builtIn) {
                case PII_DETECTOR ->
                    new PiiDetectorGuard(gc.pii(), order);
                case KEYWORD_BLOCKER ->
                    new KeywordBlockerGuard(gc.keywords(), true, order);
                case MAX_TOKENS ->
                    new MaxTokensLimiterGuard(
                        gc.maxInputTokens() != null ? gc.maxInputTokens() : 8000, order);
                case SYSTEM_PROMPT_INJECTOR ->
                    new SystemPromptInjectorGuard(gc.systemPrompt(), order);
            };
            guards.add(guard);
            log.debug("Registered request guard: type='{}' order={}", gc.type(), order);
        }

        return guards;
    }
}
