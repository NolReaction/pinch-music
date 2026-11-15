import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.11.0")
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

android {
    namespace = "music.pinch"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    externalNativeBuild.cmake {
        path = file("src/androidMain/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
    defaultConfig {
        applicationId = "music.pinch"
        externalNativeBuild.cmake.arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }
    sourceSets["main"].assets.srcDir(rootProject.file("media"))
    sourceSets["test"].resources.srcDir("src/androidUnitTest/resources")
    androidResources.noCompress += listOf("wav", "m4a", "mp4")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources {
    packageOfResClass = "music.pinch.resources"
}
