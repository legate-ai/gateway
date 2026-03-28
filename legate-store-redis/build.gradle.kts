plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "Legate Redis Store — Redis-backed cache and rate limiter"

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

    // Reactive Redis (Lettuce + Spring Data Redis)
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    // Jackson for JSON serialization of cached responses
    implementation("tools.jackson.core:jackson-databind")
}
