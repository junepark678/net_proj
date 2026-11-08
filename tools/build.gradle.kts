plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
}

application {
    mainClass.set("dev.junepark.oniontalkie.simulator.SimulatorCliKt")
}

tasks.register<JavaExec>("simulate") {
    group = "application"
    description = "Runs call simulations or a headless desktop peer for an Android Tor call."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir(rootProject.projectDir)
    standardInput = System.`in`
}

tasks.register<JavaExec>("torLoopbackTest") {
    group = "verification"
    description = "Runs two local Tor clients and an authenticated onion-service audio call."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir(rootProject.projectDir)
    args(
        "--preset", "tor-real",
        "--seconds", "5",
        "--out", rootProject.layout.buildDirectory.dir("tor-loopback").get().asFile.absolutePath,
    )
}
