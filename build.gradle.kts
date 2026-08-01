plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless") version "6.25.0"
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

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
