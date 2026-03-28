plugins {
    `java-platform`
    `maven-publish`
}

description = "Legate AI Gateway - Bill of Materials"

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.findProperty("springBootVersion")}"))
    api(platform("com.fasterxml.jackson:jackson-bom:${rootProject.findProperty("jacksonVersion")}"))
    api(platform("io.projectreactor:reactor-bom:${rootProject.findProperty("projectReactorVersion")}"))
    api(platform("org.testcontainers:testcontainers-bom:${rootProject.findProperty("testcontainersVersion")}"))

    constraints {
        api("io.legate:legate-core:${project.version}")
        api("io.legate:legate-provider-openai:${project.version}")
        api("io.legate:legate-provider-anthropic:${project.version}")
        api("io.legate:legate-provider-azure:${project.version}")
        api("io.legate:legate-provider-bedrock:${project.version}")
        api("io.legate:legate-provider-vertexai:${project.version}")
        api("io.legate:legate-provider-ollama:${project.version}")
        api("io.legate:legate-spring-boot-starter:${project.version}")
        api("io.legate:legate-server:${project.version}")
        api("io.legate:legate-store-redis:${project.version}")
        api("io.legate:legate-store-postgres:${project.version}")

        // Additional dependencies
        api("com.github.ben-manes.caffeine:caffeine:${rootProject.findProperty("caffeineVersion")}")
        api("com.aventrix.jnanoid:jnanoid:${rootProject.findProperty("nanoIdVersion")}")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["javaPlatform"])

            pom {
                name.set("Legate BOM")
                description.set("Bill of Materials for Legate AI Gateway")
                url.set("https://github.com/legate-ai/legate")

                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("legate-team")
                        name.set("Legate Team")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/legate-ai/legate.git")
                    developerConnection.set("scm:git:ssh://github.com/legate-ai/legate.git")
                    url.set("https://github.com/legate-ai/legate")
                }
            }
        }
    }
}
