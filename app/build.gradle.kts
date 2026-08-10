plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.pgotta.stridulate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pgotta.stridulate"
        minSdk = 26
        targetSdk = 34
        // Final Stage-J Android consolidation: frozen J.1 + Perch 2.0, 88 species.
        versionCode = 15
        versionName = "3.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    // The legacy TFLite asset remains uncompressed for rollback/audit compatibility.
    androidResources { noCompress += "tflite" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Retained for legacy source/assets while v3's active classifier is frozen J.1/Perch ONNX.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
