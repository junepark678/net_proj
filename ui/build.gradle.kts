plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.junepark.oniontalkie.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    jvm("desktop")

    sourceSets {
        androidMain.dependencies {
            implementation(libs.zxing.core)
        }
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.material3)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.zxing.core)
            }
        }
    }
}
