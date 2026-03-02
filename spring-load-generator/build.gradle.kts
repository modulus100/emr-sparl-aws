import org.gradle.api.tasks.testing.Test

plugins {
    java
    alias(libs.plugins.spring.boot)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.kafka.clients)
    implementation(libs.protobuf.java)

    testImplementation(libs.spring.boot.starter.test)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

sourceSets {
    named("main") {
        java.srcDir("src/generated/java")
    }
}

val generateProtobuf by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Generate Java protobuf classes for spring-load-generator using buf"
    workingDir = rootProject.projectDir
    commandLine("buf", "generate", "--template", "spring-load-generator/buf.gen.spring.yaml")
    inputs.file(rootProject.file("buf.yaml"))
    inputs.file(rootProject.file("proto/oracle_cdc.proto"))
    inputs.file(project.file("buf.gen.spring.yaml"))
    outputs.dir(project.file("src/generated/java"))
}

tasks.named("compileJava") {
    dependsOn(generateProtobuf)
}

tasks.withType(Test::class).configureEach {
    useJUnitPlatform()
}
