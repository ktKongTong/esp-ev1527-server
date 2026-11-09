import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    // AGP 9 requires the dedicated Android KMP library plugin instead of
    // com.android.library + androidTarget().
    android {
        namespace = "dev.koft.ev1527.gui.shared"
        // Compose 1.13 requires Android API 37.1+; the KMP Android plugin
        // expresses the minor level through the compileSdk spec.
        compileSdk {
            version = release(libs.versions.android.compile.sdk.get().toInt()) {
                minorApiLevel = libs.versions.android.sdk.minor.get().toInt()
            }
        }
        minSdk = libs.versions.android.min.sdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        // Compose Resources ships the bundled CJK font as an Android asset.
        androidResources {
            enable = true
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":contract"))
            api(libs.compose.runtime)
            implementation(libs.compose.runtime.saveable)
            api(libs.compose.animation)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.backhandler)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            // DataStore Preferences is KMP (Android, JVM, native, wasmJs).
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.kotlinx.browser)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.koft.ev1527.gui.resources"
}

dependencies {
    add("kspAndroid", libs.androidx.room3.compiler)
}
