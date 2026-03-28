plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "Legate PostgreSQL Store — PostgreSQL-backed audit log, virtual keys, spend tracking"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${rootProject.findProperty("springBootVersion")}")
    }
}

dependencies {
    // Core module (SPI interfaces)
    api(project(":legate-core"))

    // Spring Boot auto-configuration support
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Spring Data R2DBC + PostgreSQL R2DBC driver
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.postgresql:r2dbc-postgresql")

    // PostgreSQL JDBC driver (Flyway uses JDBC for schema migrations)
    implementation("org.postgresql:postgresql")

    // Flyway for schema migrations
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Caffeine for local key-verification cache (avoids DB hit on every request)
    implementation("com.github.ben-manes.caffeine:caffeine:${rootProject.findProperty("caffeineVersion")}")

    // Jackson for JSON serialization
    implementation("tools.jackson.core:jackson-databind")
}
