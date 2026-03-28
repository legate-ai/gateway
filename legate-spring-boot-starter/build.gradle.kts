plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "Legate Spring Boot Starter"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${rootProject.findProperty("springBootVersion")}")
    }
}

dependencies {
    // Core module
    api(project(":legate-core"))
    api(project(":legate-provider-openai"))
    api(project(":legate-provider-anthropic"))

    // Spring Boot
    api("org.springframework.boot:spring-boot-starter")
    // WebFlux needed at runtime for LegateClient (provider HTTP calls)
    api("org.springframework.boot:spring-boot-starter-webflux")

    // Caffeine for guard pipeline default
    implementation("com.github.ben-manes.caffeine:caffeine:${rootProject.findProperty("caffeineVersion")}")

    // Micrometer — actuator metrics for the embedded client
    implementation("io.micrometer:micrometer-core")

    // Micrometer Tracing — provides Tracer SPI; actual backend (OTel/Brave) is optional
    compileOnly("io.micrometer:micrometer-tracing")

    // Spring Security Crypto — BCrypt for virtual key hashing (no web/authentication dependencies)
    implementation("org.springframework.security:spring-security-crypto")

    // Configuration processor
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}
