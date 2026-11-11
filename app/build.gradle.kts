plugins {
    alias(libs.plugins.agp.app)
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep private signing material outside the repository.
val releaseSigning = listOf("SMARTPEN_KEYSTORE", "SMARTPEN_STORE_PASSWORD", "SMARTPEN_KEY_ALIAS", "SMARTPEN_KEY_PASSWORD")
    .map { providers.environmentVariable(it).orNull }
require(releaseSigning.all { it.isNullOrBlank() } || releaseSigning.all { !it.isNullOrBlank() }) {
    "Set all four SMARTPEN signing environment variables, or leave all unset."
}

android {
    namespace = "xiaomismartpen.plus"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "xiaomismartpen.plus"
        minSdk = 28
        targetSdk = 37
        versionCode = 6
        versionName = "1.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    if (releaseSigning.all { !it.isNullOrBlank() }) {
        signingConfigs.create("release") {
            storeFile = file(releaseSigning[0]!!)
            storePassword = releaseSigning[1]
            keyAlias = releaseSigning[2]
            keyPassword = releaseSigning[3]
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigning.all { !it.isNullOrBlank() }) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    // Provided by the Xposed framework at runtime, never packaged.
    compileOnly(libs.xposed.api)
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
