// SPDX-License-Identifier: GPL-3.0-or-later
// The SoundPower parameter protocol (docs/protocol.md) in Kotlin: pure JVM, so the golden-vector
// tests run without a device.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}

val vectors = rootProject.layout.projectDirectory.dir("protocol/vectors")
tasks.test {
    inputs.dir(vectors).withPropertyName("vectors").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("soundpower.vectors", vectors.asFile.absolutePath)
}
