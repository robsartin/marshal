plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless") version "8.10.0"
}

group = "com.robsartin"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform { includeEngines("junit-jupiter", "jqwik") }
    jvmArgs("-ea")                     // assertions on: the invariant discipline needs this
    finalizedBy(tasks.jacocoTestReport)
}

spotless {
    java { palantirJavaFormat() }
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.80".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.65".toBigDecimal() }
        }
    }
}

// ADR 0016: production code depends only on the JDK. Fail the build (locally and in CI)
// if any external dependency is declared on a production configuration. Test-scope
// configurations (testImplementation, etc.) are intentionally not checked.
val verifyNoRuntimeDependencies by tasks.registering {
    group = "verification"
    description = "Fails if production code declares any external dependency (ADR 0016: JDK-only)."
    doLast {
        val productionConfigs = listOf("api", "implementation", "compileOnly", "runtimeOnly")
        val offenders = productionConfigs.flatMap { name ->
            (configurations.findByName(name)?.dependencies ?: emptyList<Dependency>())
                .filterIsInstance<ExternalModuleDependency>()
                .map { "$name: ${it.group}:${it.name}:${it.version ?: ""}" }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Production code must depend only on the JDK (ADR 0016). " +
                    "Remove these or record a superseding ADR:\n  " +
                    offenders.joinToString("\n  ")
            )
        }
    }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification, verifyNoRuntimeDependencies) }
