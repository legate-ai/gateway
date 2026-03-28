package io.legate.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageTest {

    @Test
    void shouldCalculateTotalTokens() {
        Usage usage = Usage.of(100, 50);

        assertThat(usage.promptTokens()).isEqualTo(100);
        assertThat(usage.completionTokens()).isEqualTo(50);
        assertThat(usage.totalTokens()).isEqualTo(150);
    }

    @Test
    void shouldAddUsageRecords() {
        Usage usage1 = Usage.of(100, 50);
        Usage usage2 = Usage.of(25, 10);

        Usage combined = usage1.add(usage2);

        assertThat(combined.promptTokens()).isEqualTo(125);
        assertThat(combined.completionTokens()).isEqualTo(60);
        assertThat(combined.totalTokens()).isEqualTo(185);
    }

    @Test
    void shouldHandleNullInAdd() {
        Usage usage = Usage.of(100, 50);

        Usage result = usage.add(null);

        assertThat(result).isEqualTo(usage);
    }
}
