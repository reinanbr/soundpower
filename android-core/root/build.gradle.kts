// SPDX-License-Identifier: GPL-3.0-or-later
// Root access (libsu): Magisk detection, module install, the state file, dumpsys.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.reinanbr.soundpower.root"
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
    api(project(":android-core:protocol"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.libsu.core)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
