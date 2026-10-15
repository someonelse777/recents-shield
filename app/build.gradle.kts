plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.recentsshield"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.recentsshield"
        minSdk = 29
        targetSdk = 34
        versionCode = 12
        versionName = "0.12"
    }

    // A fixed signing key that lives in the repo, so every GitHub build can be
    // installed as an update over the previous one. It only signs this personal
    // sideloaded app; it is not a secret.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/shared-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
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
