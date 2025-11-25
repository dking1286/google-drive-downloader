plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.graalvm.buildtools.native") version "0.11.1"
    application
}

group = "dev.dking"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // HTTP & Google APIs
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.api-client:google-api-client:2.7.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240914-2.0.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.36.0")

    // Database & serialization
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // CLI & utilities
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

application {
    mainClass.set("dev.dking.googledrivedownloader.MainKt")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("google-drive-downloader")
            mainClass.set("dev.dking.googledrivedownloader.MainKt")

            buildArgs.addAll(listOf(
                "--enable-http",
                "--enable-https",
                "--enable-url-protocols=http,https",
                "--enable-all-security-services",
                "-O3",
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time=com.google.api.client",
                "--initialize-at-run-time=com.google.api.client.http.javanet.NetHttpTransport",
                "--gc=serial"
            ))

            verbose.set(true)
        }
    }

    // GraalVM agent for automatic metadata generation
    agent {
        defaultMode.set("standard")
        metadataCopy {
            inputTaskNames.add("test")
            outputDirectories.add("src/main/resources/META-INF/native-image")
            mergeWithExisting.set(true)
        }
    }

    binaries.all {
        resources.autodetect()
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
