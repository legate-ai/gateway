package io.legate.store.postgres;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.audit.AuditLogger;
import io.legate.core.config.LegateConfig;
import io.legate.core.event.EventBus;
import io.legate.core.key.VirtualKeyStore;
import io.legate.core.meter.SpendTracker;
import io.legate.store.postgres.mapper.AuditEventMapper;
import io.legate.store.postgres.mapper.VirtualKeyMapper;
import io.legate.store.postgres.repository.AuditEventR2dbcRepository;
import io.legate.store.postgres.repository.RequestLogR2dbcRepository;
import io.legate.store.postgres.repository.SpendTrackingR2dbcRepository;
import io.legate.store.postgres.repository.VirtualKeyR2dbcRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Auto-configuration for PostgreSQL-backed Legate stores.
 *
 * <p>Activates when {@code spring-boot-starter-data-r2dbc} (and thus
 * {@link DatabaseClient}) is on the classpath AND
 * {@code legate.store.type=postgres} is set.</p>
 *
 * <h3>Beans provided (all {@code @ConditionalOnMissingBean})</h3>
 * <ul>
 *   <li>{@code legateVirtualKeyStore}  — {@link PostgresVirtualKeyStore}</li>
 *   <li>{@code legateAuditLog}         — {@link PostgresAuditLogger}</li>
 *   <li>{@code legateSpendTracker}     — {@link PostgresSpendTracker}</li>
 *   <li>{@code legateRequestLog}       — {@link PostgresRequestLog}</li>
 * </ul>
 *
 * <p>Mapper beans ({@link AuditEventMapper}, {@link VirtualKeyMapper}) are
 * registered unconditionally because they are needed by all PostgreSQL stores
 * and have no in-memory counterpart to conflict with.</p>
 */
@AutoConfiguration(
        beforeName = "io.legate.spring.autoconfigure.LegateAutoConfiguration"
)
@ConditionalOnClass(DatabaseClient.class)
@ConditionalOnProperty(prefix = "legate.store", name = "type", havingValue = "postgres")
public class PostgresAutoConfiguration {

    @Bean
    public AuditEventMapper auditEventMapper(ObjectMapper objectMapper) {
        return new AuditEventMapper(objectMapper);
    }

    @Bean
    public VirtualKeyMapper virtualKeyMapper(ObjectMapper objectMapper) {
        return new VirtualKeyMapper(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(VirtualKeyStore.class)
    public VirtualKeyStore legateVirtualKeyStore(
            VirtualKeyR2dbcRepository repository,
            VirtualKeyMapper mapper,
            ObjectMapper objectMapper
    ) {
        return new PostgresVirtualKeyStore(repository, mapper, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(AuditLogger.class)
    public AuditLogger legateAuditLog(
            AuditEventR2dbcRepository repository,
            AuditEventMapper mapper
    ) {
        return new PostgresAuditLogger(repository, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(SpendTracker.class)
    public SpendTracker legateSpendTracker(
            LegateConfig legateConfig,
            SpendTrackingR2dbcRepository repository,
            DatabaseClient db
    ) {
        return new PostgresSpendTracker(legateConfig.spendControl(), repository, db);
    }

    @Bean
    @ConditionalOnMissingBean(PostgresRequestLog.class)
    public PostgresRequestLog legateRequestLog(
            RequestLogR2dbcRepository repository,
            EventBus eventBus
    ) {
        return new PostgresRequestLog(repository, eventBus);
    }
}
