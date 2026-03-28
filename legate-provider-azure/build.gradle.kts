plugins {
    `java-library`
}

description = "Legate Provider - Azure OpenAI"

dependencies {
    // Core module
    api(project(":legate-core"))

    // Jackson 3.x
    implementation("tools.jackson.core:jackson-databind")

    // Testing
    testImplementation("org.wiremock:wiremock:${rootProject.findProperty("wiremockVersion")}")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.18")
}
