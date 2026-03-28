plugins {
    `java-library`
    `maven-publish`
}

description = "Legate Core - Domain logic with zero Spring dependencies"

dependencies {
    val jacksonVersion = rootProject.findProperty("jacksonVersion") as String
    val caffeineVersion = rootProject.findProperty("caffeineVersion") as String
    val nanoIdVersion = rootProject.findProperty("nanoIdVersion") as String
    val resilience4jVersion = rootProject.findProperty("resilience4jVersion") as String

    // Jackson for JSON serialization (annotations only in core)
    // Jackson 3.x changed group IDs: databind moved to tools.jackson.core
    // jackson-annotations stays on com.fasterxml.jackson.core (intentionally unchanged in 3.x)
    // jackson-datatype-jsr310 is managed by Spring Boot BOM in modules that need it
    api("tools.jackson.core:jackson-databind:$jacksonVersion")
    api("com.fasterxml.jackson.core:jackson-annotations:2.18.3")

    // Caffeine for in-memory cache
    api("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")

    // Resilience4j — circuit breaker and rate limiter (pure Java, no Spring/Reactor dependency)
    api("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")
    api("io.github.resilience4j:resilience4j-ratelimiter:$resilience4jVersion")

    // NanoID for unique ID generation
    implementation("com.aventrix.jnanoid:jnanoid:$nanoIdVersion")

    // SLF4J API only (no implementation)
    api("org.slf4j:slf4j-api:${rootProject.findProperty("slf4jVersion")}")

    // Test dependencies
    testImplementation("ch.qos.logback:logback-classic:1.5.3")
}

java {
    withJavadocJar()
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Legate Core")
                description.set("Core domain logic for Legate AI Gateway")
                url.set("https://github.com/legate-ai/legate")

                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
