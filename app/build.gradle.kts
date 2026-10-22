plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "com.enhancepro.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.enhancepro.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            val store = rootProject.file("keystore/enhancepro.jks")
            if (store.exists()) {
                signingConfig = signingConfigs.create("release").apply {
                    storeFile = store
                    storePassword = (project.findProperty("EP_STORE_PASSWORD") as String?) ?: "enhancepro"
                    keyAlias = "enhancepro"
                    keyPassword = (project.findProperty("EP_KEY_PASSWORD") as String?) ?: "enhancepro"
                }
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.opencv:opencv:4.10.0")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
