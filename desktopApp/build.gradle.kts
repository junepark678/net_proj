import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFilePermissions
import org.gradle.api.file.FileCopyDetails

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val torPlatform = when {
    osName.contains("linux") && osArch in setOf("x86_64", "amd64") -> "linux-x86_64"
    osName.contains("linux") && osArch in setOf("aarch64", "arm64") -> "linux-aarch64"
    osName.contains("mac") && osArch in setOf("x86_64", "amd64") -> "macos-x86_64"
    osName.contains("mac") && osArch in setOf("aarch64", "arm64") -> "macos-aarch64"
    osName.contains("windows") && osArch in setOf("x86_64", "amd64") -> "windows-x86_64"
    else -> error("No Tor Expert Bundle is configured for $osName/$osArch")
}

val torAppResources = layout.buildDirectory.dir("compose/tor-app-resources")
val fetchTorBundle by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("bash", rootProject.file("tools/fetch_tor.sh").absolutePath)
}
val stageTorBundle by tasks.registering(Sync::class) {
    dependsOn(fetchTorBundle)
    from(rootProject.layout.buildDirectory.dir("tor/$torPlatform"))
    into(torAppResources.map { it.dir("common/tor/$torPlatform") })
}
val includeTorInAppImage by tasks.registering(Copy::class) {
    dependsOn(tasks.matching { it.name == "createDistributable" })
    from(layout.buildDirectory.dir("compose/tmp/prepareAppResources"))
    val appResources = layout.buildDirectory.dir("compose/binaries/main/app/OnionDial/lib/app/resources")
    into(appResources)
    eachFile(Action<FileCopyDetails> {
        if (name in setOf("tor", "lyrebird", "conjure-client")) {
            permissions(Action<ConfigurableFilePermissions> { unix("0755") })
        }
    })
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
    implementation(project(":ui"))
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.zxing.core)
}

compose.desktop {
    application {
        mainClass = "dev.junepark.oniontalkie.desktop.MainKt"
        nativeDistributions {
            packageName = "OnionDial"
            packageVersion = "0.1.0"
            appResourcesRootDir.set(torAppResources)
        }
    }
}

tasks.configureEach {
    if (name.contains("Distributable", ignoreCase = true) || name.startsWith("package")) {
        dependsOn(stageTorBundle)
    }
    if (name == "prepareAppResources") {
        dependsOn(stageTorBundle)
    }
    if (name.startsWith("package")) {
        dependsOn(includeTorInAppImage)
    }
}

// Keep packaging runtime selection independent of the build/compiler JDK. CI installs
// Java 17 for the distributable while Gradle and compilation continue on Java 21.
val onionDialJpackageJavaHome = providers.gradleProperty("onionDialJpackageJavaHome")
    .orElse(providers.environmentVariable("JAVA_HOME_17_X64"))
afterEvaluate {
    onionDialJpackageJavaHome.orNull?.let { selectedJavaHome ->
        tasks.withType<AbstractJvmToolOperationTask>().configureEach {
            javaHome.set(selectedJavaHome)
        }
    }
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy(includeTorInAppImage)
}
