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
    into(layout.buildDirectory.dir("compose/binaries/main/app/OnionDial/lib/app/resources"))
}

kotlin {
    jvmToolchain(21)
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

tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy(includeTorInAppImage)
}
