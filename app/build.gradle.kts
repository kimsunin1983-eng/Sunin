plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.livesubtitle"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.livesubtitle"
        minSdk = 29
        targetSdk = 34
        versionCode = 23
        versionName = "6.0"
    }

    // 빌드할 때마다 같은 키로 서명해야 새 버전을 삭제 없이 덮어 설치할 수 있음
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
