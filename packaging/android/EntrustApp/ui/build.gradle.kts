plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "kr.co.synapseai.entrust.ui"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":core"))
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    api(bom)
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")
    api("androidx.compose.ui:ui-tooling-preview")
    api("androidx.activity:activity-compose:1.9.3")
    api("androidx.fragment:fragment-ktx:1.8.5")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    api("androidx.biometric:biometric:1.1.0")
    api("androidx.camera:camera-camera2:1.4.1")
    api("androidx.camera:camera-lifecycle:1.4.1")
    api("androidx.camera:camera-view:1.4.1")
    api("com.google.zxing:core:3.5.3")
}
