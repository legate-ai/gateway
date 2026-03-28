plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    application
}

description = "Legate Server - Standalone AI Gateway Application"

application {
    mainClass.set("io.legate.server.LegateApplication")
}

dependencies {
    // Legate modules
    implementation(project(":legate-core"))
    implementation(project(":legate-provider-openai"))
    implementation(project(":legate-provider-anthropic"))
    implementation(project(":legate-provider-azure"))
    implementation(project(":legate-provider-ollama"))
    implementation(project(":legate-spring-boot-starter"))

    // Spring Boot WebFlux
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Micrometer Prometheus registry for metrics export
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Jackson 3.x — databind group ID changed to tools.jackson.core
    // Note: jackson-datatype-jsr310 is now built into jackson-databind in Jackson 3.x
    implementation("tools.jackson.core:jackson-databind")

    // NanoID for request IDs
    implementation("com.aventrix.jnanoid:jnanoid:${rootProject.findProperty("nanoIdVersion")}")

    // Micrometer Tracing + OpenTelemetry bridge
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Logging — version managed by Spring Boot BOM
    implementation("ch.qos.logback:logback-classic")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.wiremock:wiremock:${rootProject.findProperty("wiremockVersion")}")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("legate-server.jar")
}
