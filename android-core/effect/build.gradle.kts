// SPDX-License-Identifier: GPL-3.0-or-later
// The control channel: an AudioEffect instance of SoundPower on a private session (HiddenApiBypass).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.reinanbr.soundpower.effect"
    // Android 17 QPR2 (37.2): Compose 1.13 needs at least 37.1.
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":android-core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.hiddenapibypass)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
