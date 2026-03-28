plugins {
    `java-library`
}

description = "Legate Provider - Anthropic Claude"

dependencies {
    // Core module
    api(project(":legate-core"))

    // Jackson 3.x — databind group ID changed to tools.jackson.core
    implementation("tools.jackson.core:jackson-databind")

    // Testing
    testImplementation("org.wiremock:wiremock:${rootProject.findProperty("wiremockVersion")}")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.18")
}
