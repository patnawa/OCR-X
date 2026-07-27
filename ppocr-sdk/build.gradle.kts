plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.paddle.ocr"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("proguard-rules.pro")
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    // Official OpenCV 4.11 (modern NDK/libc++) — 4.5.3 needed a libc++ symbol
    // (__sfp_handle_exceptions) that ONNX Runtime's newer libc++_shared.so drops.
    // Exposed as `api` so consumers can run their own OpenCV passes (deskew,
    // ruled-line table detection) against the same native library this loads.
    api("org.opencv:opencv:4.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")

    // JVM unit tests for the parts of the pipeline that are pure Kotlin
    // (recognition batch planning).
    testImplementation("junit:junit:4.13.2")
}
