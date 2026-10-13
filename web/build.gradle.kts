plugins {
    // The Kotlin plugins are already on the classpath from the root build (apply false),
    // so apply them here without re-declaring the version.
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
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
        val commonMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":shared-ui"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jsMain by getting {
            // The shared engine and presentation layer come from core's JS target.
            // Browser-backed implementations of the JVM-only infra (HTTP, storage) live here.
            dependencies {
                implementation(project(":core"))
                implementation(project(":shared-ui"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.markdown.renderer.m3)
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

// Serve the compiled Compose app from the existing PC gateway, with no extra public listener.
tasks.named<org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack>("jsBrowserProductionWebpack") {
    outputFileName = "friday-web.js"
}
tasks.named<org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack>("jsBrowserDevelopmentWebpack") {
    outputFileName = "friday-web.js"
}
tasks.register<Sync>("stageGateway") {
    dependsOn("jsBrowserDistribution")
    from(layout.buildDirectory.dir("dist/js/productionExecutable"))
    into(rootProject.layout.projectDirectory.dir("desktop/workspace/web-dist"))
}
