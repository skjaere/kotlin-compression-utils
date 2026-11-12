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

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

dependencies {
    implementation(libs.slf4j.api)
    api(libs.kotlinx.coroutines.core) // CoroutineDispatcher is in the stream adapters' constructors
    implementation(libs.xz)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.slf4j.simple)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                licenses {
                    license {
                        name = "PolyForm Noncommercial License 1.0.0"
                        url = "https://polyformproject.org/licenses/noncommercial/1.0.0"
                    }
                }
            }
        }
    }
}

// The jars carry the licence in a folder of their own under META-INF, so they
// don't clash with another library's in an app that packs several.
tasks.withType<Jar>().configureEach {
    into("META-INF/${project.name}") {
        from("LICENSE.md")
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
