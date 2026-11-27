plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val verCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
android {
    namespace = "com.talk.translator"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.talk.translator"
        minSdk = 24
        targetSdk = 34
        versionCode = verCode
        versionName = "1.0.$verCode"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("talking.keystore")
            storePassword = "android"
            keyAlias = "talking"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
configurations.all { exclude(group = "com.google.guava", module = "listenablefuture") }
dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
}
