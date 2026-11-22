plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    kotlin("android")
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
android {
    namespace = "local.abu"
    compileSdk = 37
    defaultConfig {
        applicationId = "local.abu"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    implementation(project(":shared"))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
}
