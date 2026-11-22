plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.core)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.mock)
            implementation(libs.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
        }
    }
}
android {
    namespace = "local.abu.shared"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
