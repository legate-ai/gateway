# Multi-stage build for Legate AI Gateway

# Stage 1: Build
FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /build

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Copy all module build files
COPY legate-bom/build.gradle.kts legate-bom/
COPY legate-core/build.gradle.kts legate-core/
COPY legate-provider-openai/build.gradle.kts legate-provider-openai/
COPY legate-provider-anthropic/build.gradle.kts legate-provider-anthropic/
COPY legate-spring-boot-starter/build.gradle.kts legate-spring-boot-starter/
COPY legate-server/build.gradle.kts legate-server/

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY legate-core/src legate-core/src
COPY legate-provider-openai/src legate-provider-openai/src
COPY legate-provider-anthropic/src legate-provider-anthropic/src
COPY legate-spring-boot-starter/src legate-spring-boot-starter/src
COPY legate-server/src legate-server/src

# Build the application
RUN ./gradlew :legate-server:bootJar -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-alpine

LABEL org.opencontainers.image.title="Legate AI Gateway"
LABEL org.opencontainers.image.description="High-performance AI gateway for LLM providers"
LABEL org.opencontainers.image.version="0.1.0-SNAPSHOT"
LABEL org.opencontainers.image.licenses="Apache-2.0"

WORKDIR /app

# Create non-root user
RUN addgroup -S legate && adduser -S legate -G legate

# Copy JAR from build stage
COPY --from=build /build/legate-server/build/libs/legate-server.jar /app/legate.jar

# Change ownership
RUN chown -R legate:legate /app

# Switch to non-root user
USER legate

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

# JVM options for containerized environment
ENV JAVA_OPTS="-XX:+UseZGC \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseContainerSupport \
               -Djava.security.egd=file:/dev/./urandom"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar legate.jar"]
