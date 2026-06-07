plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.6" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "io.legate"
    version = findProperty("legateVersion") as String

    repositories {
        mavenCentral()
    }
}

subprojects {
    // Skip legate-bom - it uses java-platform instead
    if (name == "legate-bom") {
        return@subprojects
    }

    apply(plugin = "java")
    apply(plugin = "jacoco")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf(
                "-parameters"
            )
        )
    }

    tasks.withType<Javadoc> {
        isFailOnError = false
        val opts = options as StandardJavadocDocletOptions
        opts.addStringOption("source", "25")
        opts.quiet()
    }

    tasks.withType<Test> {
        useJUnitPlatform()

        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }

    tasks.withType<JacocoReport> {
        dependsOn(tasks.test)

        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    // Common dependencies for all subprojects
    dependencies {
        val slf4jVersion = rootProject.findProperty("slf4jVersion") as String
        val junitVersion = rootProject.findProperty("junitVersion") as String
        val mockitoVersion = rootProject.findProperty("mockitoVersion") as String
        val assertjVersion = rootProject.findProperty("assertjVersion") as String

        // Logging
        implementation("org.slf4j:slf4j-api:$slf4jVersion")

        // Apache Commons — version pinned in gradle.properties (legate-core has no BOM)
        val commonsLang3Version = rootProject.findProperty("commonsLang3Version") as String
        implementation("org.apache.commons:commons-lang3:$commonsLang3Version")

        // Testing
        testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
        testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
        // Required by Gradle 9.x to locate the JUnit Platform launcher
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        testImplementation("org.mockito:mockito-core:$mockitoVersion")
        testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
        testImplementation("org.assertj:assertj-core:$assertjVersion")
    }
}

// Root project tasks
tasks.register("cleanAll") {
    dependsOn(subprojects.map { it.tasks.named("clean") })
}

tasks.register("buildAll") {
    dependsOn(subprojects.map { it.tasks.named("build") })
}

tasks.register("testAll") {
    dependsOn(subprojects.map { it.tasks.named("test") })
}

// Jacoco aggregate report
tasks.register<JacocoReport>("jacocoRootReport") {
    dependsOn(subprojects.map { it.tasks.named("test") })

    subprojects {
        this@subprojects.plugins.withType<JacocoPlugin>().configureEach {
            this@subprojects.tasks.matching { it.extensions.findByType<JacocoTaskExtension>() != null }.configureEach {
                sourceSets(this@subprojects.the<SourceSetContainer>().named("main").get())
                executionData(this)
            }
        }
    }

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
