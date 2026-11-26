// Kotlin 플러그인은 여기서 한 번만 불러온다(하위 모듈마다 따로 불러오면 경고 · 오류).
plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
}
