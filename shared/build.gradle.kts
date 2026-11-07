plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.junepark.oniontalkie.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        withHostTestBuilder {}
    }
    jvm("desktop")

    sourceSets {
        // Concentus is a plain JVM jar, so it cannot live in commonMain. Both
        // targets are JVM, hence one intermediate source set serves them both.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.concentus)
            }
        }
        val jvmSharedTest by creating {
            dependsOn(commonTest.get())
        }
        androidMain.get().dependsOn(jvmSharedMain)
        getByName("desktopMain").dependsOn(jvmSharedMain)
        getByName("androidHostTest").dependsOn(jvmSharedTest)
        getByName("desktopTest").dependsOn(jvmSharedTest)
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
