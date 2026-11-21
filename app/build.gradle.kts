// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The app and the Magisk module share the version in VERSION; the app bundles the module zip
// that magisk-module/build.sh made from the same tree.
val spVersion = providers.fileContents(rootProject.layout.projectDirectory.file("VERSION")).asText.get().trim()
val spVersionCode = spVersion.split('.').map(String::toInt).let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

android {
    namespace = "com.reinanbr.soundpower"
    // Android 17 QPR2 (37.2): Compose 1.13 needs at least 37.1.
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.reinanbr.soundpower"
        minSdk = 28
        targetSdk = 37
        versionCode = spVersionCode
        versionName = spVersion
        buildConfigField("String", "MODULE_VERSION", "\"v$spVersion\"")
        buildConfigField("int", "MODULE_VERSION_CODE", "$spVersionCode")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkDependencies = true
        // New library versions are checked by hand at each phase (see gradle/libs.versions.toml).
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

/** Copies the module zip into the generated assets as soundpower-module.zip. */
abstract class BundleModuleTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val moduleZip: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val zip = moduleZip.singleFile
        if (!zip.isFile) {
            throw GradleException("$zip is missing; build the Magisk module first: ANDROID_NDK_HOME=<ndk> magisk-module/build.sh")
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        zip.copyTo(File(out, "soundpower-module.zip"))
    }
}

val bundleModule = tasks.register<BundleModuleTask>("bundleMagiskModule") {
    moduleZip.from(rootProject.layout.projectDirectory.file("out/soundpower-module-v$spVersion.zip"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleModule, BundleModuleTask::outputDir)
    }
}

dependencies {
    implementation(project(":android-core:root"))
    implementation(project(":android-core:effect"))
    implementation(project(":android-core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
