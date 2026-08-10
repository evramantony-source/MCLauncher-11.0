plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
val targetAbi = providers.gradleProperty("mclauncherAbi").orElse("arm64-v8a").get()
val alphaKeystore = rootProject.file(".ci-signing/mclauncher-alpha-debug.jks")
val accountKeystore = rootProject.file(".ci-signing/mclauncher-account.jks")
val privateAccountBuild = providers.gradleProperty("mclauncherPrivateAccounts")
    .orElse(providers.environmentVariable("MCLAUNCHER_PRIVATE_ACCOUNTS"))
    .orElse("false")
    .map { it.toBooleanStrict() }
    .get()
val accountStorePassword = providers.environmentVariable("MCLAUNCHER_SIGNING_STORE_PASSWORD").orElse("").get()
val accountKeyAlias = providers.environmentVariable("MCLAUNCHER_SIGNING_KEY_ALIAS").orElse("").get()
val accountKeyPassword = providers.environmentVariable("MCLAUNCHER_SIGNING_KEY_PASSWORD").orElse("").get()
val curseForgeApiKey = providers.gradleProperty("curseforgeApiKey")
    .orElse(providers.environmentVariable("CURSEFORGE_API_KEY"))
    .orElse("")
    .get()
val escapedCurseForgeApiKey = curseForgeApiKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
require(targetAbi in supportedAbis) {
    "Unsupported mclauncherAbi=$targetAbi. Supported values: ${supportedAbis.joinToString()}"
}
if (privateAccountBuild) {
    require(accountKeystore.isFile) { "Private account build requires .ci-signing/mclauncher-account.jks" }
    require(accountStorePassword.isNotBlank()) { "Private account build requires MCLAUNCHER_SIGNING_STORE_PASSWORD" }
    require(accountKeyAlias.isNotBlank()) { "Private account build requires MCLAUNCHER_SIGNING_KEY_ALIAS" }
    require(accountKeyPassword.isNotBlank()) { "Private account build requires MCLAUNCHER_SIGNING_KEY_PASSWORD" }
}

android {
    namespace = "com.mclauncher.app"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.mclauncher.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "11.0.0-alpha21"
        buildConfigField("boolean", "PUBLIC_ALPHA_SIGNER", (!privateAccountBuild).toString())
        buildConfigField("String", "CURSEFORGE_API_KEY", "\"$escapedCurseForgeApiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20")
            }
        }
        ndk {
            abiFilters += targetAbi
        }
    }

    signingConfigs {
        create("alphaDebug") {
            storeFile = alphaKeystore
            storePassword = "mclauncher-alpha"
            keyAlias = "mclauncher-alpha"
            keyPassword = "mclauncher-alpha"
        }
        create("accountDebug") {
            storeFile = accountKeystore
            storePassword = accountStorePassword
            keyAlias = accountKeyAlias
            keyPassword = accountKeyPassword
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName(if (privateAccountBuild) "accountDebug" else "alphaDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        // These assets are already compressed. Storing them directly avoids huge APK
        // build-time expansion and makes first-run extraction substantially faster.
        noCompress += listOf("xz", "gz", "tgz")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-minecraft"))

    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("org.tukaani:xz:1.10")
    // Coil 3.1 matches this project's Kotlin 2.1.10 compiler and compileSdk 35.
    // Newer Coil releases require a newer Kotlin compiler and/or Android SDK.
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")

    testImplementation(kotlin("test"))

    debugImplementation("androidx.compose.ui:ui-tooling")
}
