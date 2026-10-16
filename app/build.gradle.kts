plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.photounifier"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.photounifier"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // OpenCV ships native libs for 4 ABIs; keep only the phone ones to keep the APK small.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
    implementation("org.opencv:opencv:4.9.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
