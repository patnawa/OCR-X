plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tsm.ocrx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tsm.ocrx"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        vectorDrawables { useSupportLibrary = true }
        // PP-OCRv6 ships native libs (ONNX Runtime + OpenCV). Cover real phones
        // (arm64 + 32-bit arm) and the standard x86_64 emulator so the OpenCV /
        // ONNX .so files are present for the device's CPU.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // ONNX Runtime and OpenCV both ship libc++_shared.so; keep one.
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // On-device translation + language identification (free, offline after
    // a one-time per-language model download).
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // Image loading for previews.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Crop-before-OCR editor (CanHub cropper, maintained fork). Its activity
    // needs an AppCompat theme, hence the appcompat dependency.
    implementation("com.vanniktech:android-image-cropper:4.6.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // PP-OCRv6 on-device engine (ONNX Runtime + OpenCV pipeline).
    implementation(project(":ppocr-sdk"))

    debugImplementation("androidx.compose.ui:ui-tooling")

    // JVM unit tests for the pure OCR pipeline (Layout column detection, parsing).
    testImplementation("junit:junit:4.13.2")
}
