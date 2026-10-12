plugins {
    // The Kotlin plugins are already on the classpath from the root build (apply false),
    // so apply them here without re-declaring the version.
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    js(IR) {
        browser {
            testTask {
                // No headless browser in this environment; logic tests run on Node.
                enabled = false
            }
        }
        nodejs()
        binaries.executable()
        useEsModules()
        compilerOptions {
            target.set("es2015")
        }
    }

    sourceSets {
        val jsMain by getting {
            // The shared engine and presentation layer come from core's JS target.
            // Browser-backed implementations of the JVM-only infra (HTTP, storage) live here.
            dependencies {
                implementation(project(":core"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
