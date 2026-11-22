import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.dgs.codegen)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.asciidoctor)

    kotlin("plugin.lombok") version "2.4.21"
    kotlin("kapt") version "2.4.21"
}

group = "de.dmp.server"
version = File("VERSION").readText().trim()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

repositories {
    mavenCentral()

    maven {
        url = uri("https://repo.spring.io/snapshot")
    }
}

extra["snippetsDir"] = file("build/generated-snippets")
extra["springCloudVersion"] = "2026.0.0-SNAPSHOT"

dependencies {
    implementation(libs.bundles.spring.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.bundles.spring.other)

    implementation(libs.yaml)
    implementation(libs.ulid.creator)
    implementation(libs.gzip)

    compileOnly(libs.lombok)

    developmentOnly(libs.spring.boot.devtools)

    annotationProcessor(libs.lombok)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.bundles.spring.core.test)
    testImplementation(libs.bundles.spring.other.test)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.junit.jupiter)

    testCompileOnly(libs.lombok)
    testRuntimeOnly(libs.junit.platform.launcher)
    testAnnotationProcessor(libs.lombok)

    testImplementation(kotlin("test"))
}

dependencyManagement {
    imports {
        mavenBom(
            "org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}"
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

kapt {
    keepJavacAnnotationProcessors = true
}

tasks.generateJava {
    schemaPaths.add("${projectDir}/src/main/resources/graphql-client")
    packageName = "de.dmp.server.codegen"
    generateClient = true
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.test {
    outputs.dir(project.extra["snippetsDir"]!!)
}

tasks.asciidoctor {
    inputs.dir(project.extra["snippetsDir"]!!)
    dependsOn(tasks.test)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val envFile = project.file(".env")
    val settings = Properties().apply {
        if (envFile.isFile) envFile.inputStream().use { load(it) }
    }
    if (System.getenv("FILESERVER_MULTIPART_MAX_DISK_USAGE_PER_PART") == null) {
        settings.getProperty("FILESERVER_MULTIPART_MAX_DISK_USAGE_PER_PART")?.let { limit ->
            require(limit.isNotBlank()) { "FILESERVER_MULTIPART_MAX_DISK_USAGE_PER_PART must not be empty" }
            environment("FILESERVER_MULTIPART_MAX_DISK_USAGE_PER_PART", limit)
        }
    }
    systemProperty("spring.profiles.active", "dev")
    systemProperty("spring.docker.compose.enabled", "false")
}

tasks.jar { enabled = false }