plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
    applyDefaultHierarchyTemplate()

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.concentus)
                implementation(libs.bouncycastle)
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        getByName("desktopMain").dependsOn(jvmSharedMain)

        val jvmSharedTest by creating {
            dependsOn(commonTest.get())
        }
        getByName("androidHostTest").dependsOn(jvmSharedTest)
        getByName("desktopTest").dependsOn(jvmSharedTest)
    }
}
