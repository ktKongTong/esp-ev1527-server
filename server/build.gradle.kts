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

    macosArm64 {
        binaries {
            executable {
                baseName = "esp32-control-server"
                entryPoint = "com.esp32.control.server.main"
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
            // nativeMain sources import these types. Declaring them on the
            // shared source set also makes IDE analysis resolve them; a
            // per-target declaration is visible to the compiler but not to
            // the shared source set. ktor-mqtt 1.2.2 publishes both variants.
            implementation(libs.ktor.mqtt.core)
            implementation(libs.ktor.mqtt)
            // MQTT over WSS. Ktor Native has no raw TLS support, but the Curl
            // engine ships a static libcurl + OpenSSL and implements WebSockets,
            // so WSS works on linuxArm64, linuxX64 and macosArm64 without host
            // libraries or an external TLS terminator.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.curl)
        }
    }
}

dependencies {
    add("kspLinuxArm64", libs.androidx.room3.compiler)
    add("kspMacosArm64", libs.androidx.room3.compiler)
}
