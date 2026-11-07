plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "dev.junepark.oniontalkie.desktop.MainKt"
        nativeDistributions {
            packageName = "OnionTalkie"
            packageVersion = "0.1.0"
        }
    }
}
