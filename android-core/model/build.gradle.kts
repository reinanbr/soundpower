// SPDX-License-Identifier: GPL-3.0-or-later
// The app's EQ settings and the 10-band graphic layout, mapped to protocol payloads. Pure JVM.
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
    api(project(":android-core:protocol"))
    testImplementation(libs.kotlin.test.junit)
}

val vectors = rootProject.layout.projectDirectory.dir("protocol/vectors")
tasks.test {
    inputs.dir(vectors).withPropertyName("vectors").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("soundpower.vectors", vectors.asFile.absolutePath)
}
