package io.legate.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Basic smoke test to verify application context loads.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "OPENAI_API_KEY=sk-test-key",
    "ANTHROPIC_API_KEY=sk-ant-test-key",
    "management.tracing.enabled=false"
})
class LegateApplicationTest {

    @Test
    void contextLoads() {
        // Verify Spring context loads successfully
    }
}
