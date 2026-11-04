plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    // linuxArm64 is the only supported server target. ktor-mqtt's native
    // artifacts publish linuxArm64 only, and the deployment image is arm64.
    // Enable the macOS target with -PenableMacosServer=true when Xcode is present.
    linuxX64 {
        binaries {
            executable {
                baseName = "esp32-control-server"
                entryPoint = "com.esp32.control.server.main"
            }
        }
    }

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
        commonMain.dependencies {
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        nativeMain.dependencies {
            implementation(project(":contract"))
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.cors)
            implementation(libs.ktor.server.status.pages)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.mqtt.core)
            implementation(libs.ktor.mqtt)
        }
    }
}

dependencies {
    add("kspLinuxArm64", libs.androidx.room3.compiler)
    if (providers.gradleProperty("enableMacosServer").orNull == "true") {
        add("kspMacosArm64", libs.androidx.room3.compiler)
    }
}
