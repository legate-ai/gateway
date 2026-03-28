package io.legate.server.tracing;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configures the OpenTelemetry span exporter used by Micrometer Tracing.
 *
 * <p>When {@code legate.tracing.enabled=true}, this class wires an OTLP span
 * exporter pointing at the configured endpoint. Spring Boot's Micrometer Tracing
 * auto-configuration picks up the bean automatically.</p>
 *
 * <h3>Configuration properties</h3>
 * <pre>{@code
 * legate:
 *   tracing:
 *     enabled: true
 *     exporter: otlp         # otlp (gRPC) | otlp-http
 *     endpoint: http://otel-collector:4317
 *     sample-rate: 1.0
 * }</pre>
 *
 * <p>When disabled (default), no exporter is configured and Micrometer Tracing
 * operates in no-op mode.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "legate.tracing", name = "enabled", havingValue = "true")
public class TracingConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingConfig.class);

    @Value("${legate.tracing.exporter:otlp}")
    private String exporterType;

    @Value("${legate.tracing.endpoint:http://localhost:4317}")
    private String endpoint;

    @Value("${legate.tracing.timeout-seconds:10}")
    private int timeoutSeconds;

    /**
     * OTLP gRPC exporter — default for {@code legate.tracing.exporter=otlp}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "legate.tracing", name = "exporter", havingValue = "otlp", matchIfMissing = true)
    public SpanExporter otlpGrpcSpanExporter() {
        log.info("Configuring OTLP gRPC span exporter → {}", endpoint);
        return OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpoint)
            .setTimeout(Duration.ofSeconds(timeoutSeconds))
            .build();
    }

    /**
     * OTLP HTTP/Protobuf exporter — use when {@code legate.tracing.exporter=otlp-http}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "legate.tracing", name = "exporter", havingValue = "otlp-http")
    public SpanExporter otlpHttpSpanExporter() {
        log.info("Configuring OTLP HTTP span exporter → {}", endpoint);
        return OtlpHttpSpanExporter.builder()
            .setEndpoint(endpoint)
            .setTimeout(Duration.ofSeconds(timeoutSeconds))
            .build();
    }
}
