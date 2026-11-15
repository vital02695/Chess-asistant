plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chessassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chessassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    // Stockfish запускается как отдельный процесс из папки нативных библиотек,
    // поэтому библиотеки должны распаковываться на диск при установке.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
}
