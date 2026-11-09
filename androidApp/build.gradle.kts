import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 has built-in Kotlin support: no org.jetbrains.kotlin.android here.
    // The KMP plugin is intentionally not applied to the app module.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The version comes from git, so no build input repeats the release tag:
// a tagged HEAD gives "0.2.0", commits after a tag give "0.2.0-3-gabc1234",
// and a repository without tags gives the short commit id.
fun gitDescribe(): String? = runCatching {
    providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", "describe", "--tags", "--always", "--match", "v[0-9]*")
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

val appVersionName = gitDescribe()?.removePrefix("v") ?: "0.0.0"
val appVersionCode = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(appVersionName)
    ?.destructured
    ?.let { (major, minor, patch) ->
        major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
    }
    ?: 1

// CI decodes the release keystore and exports these variables. Without them the
// release build stays unsigned, so a local assembleRelease needs no keystore.
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystore,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isPresent }

android {
    namespace = "com.esp32.control.gui.android"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()
    compileSdkMinor = libs.versions.android.sdk.minor.get().toInt()

    defaultConfig {
        applicationId = "com.esp32.control"
        minSdk = libs.versions.android.min.sdk.get().toInt()
        targetSdk = libs.versions.android.target.sdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore.get())
                storePassword = releaseKeystorePassword.get()
                this.keyAlias = releaseKeyAlias.get()
                this.keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}
