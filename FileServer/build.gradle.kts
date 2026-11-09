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
version = File("FileServer/VERSION").readText().trim()
val database = "fileserver";

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
    implementation(libs.bundles.spring.mongo)
    implementation(libs.bundles.spring.other)

    implementation(libs.ulid.creator)
    implementation(libs.gzip)

    compileOnly(libs.lombok)

    developmentOnly(libs.spring.boot.devtools)
    developmentOnly(libs.spring.boot.docker.compose)

    annotationProcessor(libs.lombok)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.bundles.spring.core.test)
    testImplementation(libs.bundles.spring.mongo.test)
    testImplementation(libs.bundles.spring.other.test)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)

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
    environment(
        "SPRING_MONGODB_URI",
        System.getenv("SPRING_MONGODB_URI")
            ?: "mongodb://root:secret@localhost:27017/$database?authSource=admin"
    )
    systemProperty("spring.profiles.active", "dev")
}

tasks.jar { enabled = false }