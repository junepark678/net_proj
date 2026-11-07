plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

tasks.register("torLoopbackTest") {
    group = "verification"
    description = "Runs the live Tor smoke tests and a two-engine onion-service call."
    dependsOn(":shared:desktopTest", ":tools:torLoopbackTest")
}
