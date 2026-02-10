plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.kover)
}

group = "io.skjaere"
description = "Kotlin library for extracting metadata from RAR4, RAR5, and 7zip archives"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

dependencies {
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.slf4j.simple)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("validateArchive") {
    description = "Validates archive parsing against 7z ground truth"
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.skjaere.compressionutils.validation.ArchiveValidator")
    args = listOf(providers.gradleProperty("archivePath").getOrElse(""))
}
