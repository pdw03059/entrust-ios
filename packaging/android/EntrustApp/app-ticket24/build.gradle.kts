plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "kr.co.synapseai.entrust.ticket24"
    compileSdk = 35
    defaultConfig {
        applicationId = "kr.co.synapseai.ticket24.demo"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }
    // 서명: GitHub 비밀값(ANDROID_KEYSTORE_B64 등)이 있으면 그 열쇠, 없으면 시험용(debug) 열쇠.
    signingConfigs {
        create("demo") {
            val ks = System.getenv("ENTRUST_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("ENTRUST_KS_PASS")
                keyAlias = System.getenv("ENTRUST_KEY_ALIAS")
                keyPassword = System.getenv("ENTRUST_KEY_PASS")
            } else {
                initWith(getByName("debug"))
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("demo")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":ui"))
}
