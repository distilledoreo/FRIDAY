import org.jetbrains.kotlin.gradle.ExperimentalKotlinJsCompilerPlugin
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    @OptIn(ExperimentalKotlinJsCompilerPlugin::class)
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
            // The shared engine: the app's core module, compiled for the browser.
            // Only files that are JVM-free on the JVM side are wired in here.
            kotlin.srcDir("../core/src/main/kotlin")
            // Phone, voice and PC-server plumbing is Android/device specific and stays out of the web build.
            kotlin.exclude("**/voice/**")
            kotlin.exclude("**/tools/phone/**")
            kotlin.exclude("**/tools/phoneuse/**")
            kotlin.exclude("**/tools/PcTools.kt")
            kotlin.exclude("**/tools/WorkspaceTools.kt")
            kotlin.exclude("**/tools/CodeTools.kt")
            kotlin.exclude("**/tools/WorkspaceToolSupport.kt")
            kotlin.exclude("**/presentation/PhoneUseActivity.kt")
            kotlin.exclude("**/presentation/TaskSchedule.kt")
            kotlin.exclude("**/presentation/ChatExport.kt")
            kotlin.exclude("**/conversation/KnowledgeStore.kt")
            kotlin.exclude("**/conversation/ConversationStore.kt")
            kotlin.exclude("**/search/HttpSearchService.kt")
            kotlin.exclude("**/model/OpenAiCompatibleModelProvider.kt")
            kotlin.exclude("**/grounding/PrivateLeak.kt")
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val jsTest by getting {
            kotlin.srcDir("../core/src/test/kotlin")
            kotlin.exclude("**/voice/**")
            kotlin.exclude("**/tools/**")
            kotlin.exclude("**/search/**")
            kotlin.exclude("**/model/**")
            kotlin.exclude("**/conversation/**")
            kotlin.exclude("**/grounding/**")
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
