// SPDX-License-Identifier: GPL-3.0-or-later
// The app's audio settings (graphic and parametric EQ, tone, stereo, dynamics), their JSON form,
// the response curve, and the mapping to protocol payloads. Pure JVM.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    api(project(":android-core:protocol"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit)
}

val vectors = rootProject.layout.projectDirectory.dir("protocol/vectors")
tasks.test {
    inputs.dir(vectors).withPropertyName("vectors").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("soundpower.vectors", vectors.asFile.absolutePath)
}
