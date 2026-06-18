import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun localProperty(name: String): String {
    return localProperties.getProperty(name)
        ?: error("Missing $name in local.properties")
}

fun String.toBuildConfigString(): String {
    return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

kotlin {
    // Kotlin and Java compilation both target JDK 21 for the Android build.
    jvmToolchain(21)
}

android {
    namespace = "com.medwand.developersuite.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.medwand.developersuite.android"
        // The local MedWand SDK AAR declares API 26 as its minimum platform.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "3.0.1.0"

        buildConfigField(
            "String",
            "MW_SDK_LICENSE",
            localProperty("MW_SDK_LICENSE").toBuildConfigString()
        )

        buildConfigField(
            "String",
            "MW_SDK_PUBLIC_KEY",
            localProperty("MW_SDK_PUBLIC_KEY").toBuildConfigString()
        )
    }

    buildFeatures {
        // The application shell and workflow screens are rendered with Compose.
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}

dependencies {
    // Keep Compose library versions aligned through the BOM.
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Local MedWand Android SDK used directly by the workflow view models.
    implementation(files("libs/android-sdk-beta.aar"))

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}