import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.tailorkeys"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tailorkeys"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        // R8 shrinks, optimizes and obfuscates release builds. Keep build/outputs/mapping/release/mapping.txt
        // from every release you ship: it's the only way to read that version's crash stack traces.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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

tasks.withType<Test>().configureEach {
    maxHeapSize = "256m"
    maxParallelForks = 1
    inputs.property("spatialBenchmark", providers.environmentVariable("SPATIAL_BENCHMARK").orElse("0"))
    inputs.property("integratedLmEval", providers.environmentVariable("TK_LM_EVAL").orElse("0"))
    inputs.property("integratedLmSamples", providers.environmentVariable("TK_LM_EVAL_SENTENCES").orElse("256"))
    inputs.property("integratedLmData", providers.environmentVariable("TK_LM_DATA").orElse("../../tk-lm-data"))
    inputs.property("integratedLmTuning", providers.environmentVariable("TK_LM_EVAL_TUNE").orElse("0"))
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Last stable Compose line compatible with compileSdk 36 / AGP 8.11.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
