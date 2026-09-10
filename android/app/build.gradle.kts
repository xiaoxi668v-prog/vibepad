plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiaoxi.vibepad"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xiaoxi.vibepad"
        minSdk = 28
        targetSdk = 28
        versionCode = 14
        versionName = "0.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
