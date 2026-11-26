// ENTRUST 안드로이드 앱 3개(ENTRUST 관객 · 티켓24 · ENTRUST 관리자) — 아이폰 packaging/ios/EntrustApp 과 같은 기능 · 같은 QR 형식.
// core = 순수 Kotlin(형식 · 서명 · 검표 판정, JVM 에서 시험), ui = 세 앱 공통 안드로이드 부품.
// 안드로이드 SDK 가 없는 곳에서는 `gradle -PcoreOnly :core:test` 로 core 만 시험할 수 있다.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("com.android.library") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositories {
        // core 만 시험할 때는 구글 저장소가 필요 없음(막힌 망에서도 시험되게)
        if (!providers.gradleProperty("coreOnly").isPresent) google()
        mavenCentral()
    }
}
rootProject.name = "EntrustAndroid"
include(":core")
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":ui", ":app-entrust", ":app-ticket24", ":app-admin")
}
