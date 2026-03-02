plugins {
    id("buildlogic.java-application-conventions")
}

dependencies {
    implementation(libs.jackson2.databind)
    implementation(libs.protobuf.java)
    implementation(libs.kafka.clients)
}

sourceSets {
    named("main") {
        java.srcDir("src/generated/java")
    }
}

val generateProtobuf by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Generate Java protobuf classes and descriptor set using buf"
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/generate-protobuf.sh")
    inputs.file(rootProject.file("buf.yaml"))
    inputs.file(rootProject.file("buf.gen.yaml"))
    inputs.dir(rootProject.file("proto"))
    outputs.dir(project.file("src/generated/java"))
    outputs.file(rootProject.file("artifacts/descriptors/oracle_cdc.pb"))
}

tasks.named("compileJava") {
    dependsOn(generateProtobuf)
}

application {
    mainClass = "org.example.kafkatools.LoadGeneratorApp"
}
