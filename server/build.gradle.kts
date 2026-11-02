plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Linux/arm64 is the deployment target used by the Docker workflow. It can
    // be cross-compiled from an Apple Silicon Mac without installing full Xcode.
    // Enable the macOS target with -PenableMacosServer=true when Xcode is present.
    linuxArm64 {
        binaries {
            executable {
                baseName = "esp32-control-server"
                entryPoint = "com.esp32.control.server.main"
            }
        }
    }

    if (providers.gradleProperty("enableMacosServer").orNull == "true") {
        macosArm64 {
            binaries {
                executable {
                    baseName = "esp32-control-server"
                    entryPoint = "com.esp32.control.server.main"
                }
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":contract"))
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.cors)
            implementation(libs.ktor.server.status.pages)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
