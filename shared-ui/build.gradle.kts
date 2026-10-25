plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}
kotlin {
    jvmToolchain(21)
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    js(IR) { browser { testTask { enabled = false } }; nodejs() }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.compose.material.icons.core)
            implementation(compose.ui)
            implementation(libs.markdown.renderer.m3)
        }
        androidMain.dependencies { implementation(libs.androidx.core.ktx); implementation(libs.androidx.activity.compose) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        androidUnitTest.dependencies { implementation(libs.junit) }
    }
}
android {
    namespace = "com.localfirst.assistant.shared.ui"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
