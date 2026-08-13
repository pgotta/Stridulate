import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val bundleJ1Runtime = providers.gradleProperty("stridulate.bundleJ1Runtime")
    .map { it.toBoolean() }
    .orElse(false)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(1024 * 1024).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val prepareJ1Runtime = tasks.register("prepareJ1Runtime") {
    group = "stridulate"
    description = "Downloads and verifies the exact frozen Perch 2.0 runtime for a self-contained v0.3 APK."
    onlyIf { bundleJ1Runtime.get() }
    doLast {
        val runtimeDir = layout.projectDirectory.dir("src/main/assets/runtime").asFile
        runtimeDir.mkdirs()

        data class RuntimeFile(val name: String, val bytes: Long, val sha: String)
        val support = listOf(
            RuntimeFile("j1_stage_d_affine.bin", 541040L, "066c6cf64b165abb83af93e4b1a38a4a3ffce2fa9ec476a5b3b9695466a6d76a"),
            RuntimeFile("j1_calibration.json", 34189L, "fddecaabe0e39ebdb98eac5e804b2f77a5c9f9f25afd4510b5c740cd83e2d7f9")
        )
        support.forEach { item ->
            val file = File(runtimeDir, item.name)
            require(file.isFile) { "Missing ${item.name}. Re-extract the complete Stridulate v0.3 Android Studio package." }
            require(file.length() == item.bytes) { "${item.name} size mismatch; refusing to build." }
            require(sha256(file).equals(item.sha, ignoreCase = true)) { "${item.name} SHA-256 mismatch; refusing to build." }
        }

        val perch = RuntimeFile(
            "perch_v2_no_dft.onnx",
            413350933L,
            "4dcf71c18a147198545944bb5149697e89e3ad2e16637fa8f0edf6d13035a017"
        )
        val model = File(runtimeDir, perch.name)
        fun validPerch(): Boolean = model.isFile && model.length() == perch.bytes &&
            sha256(model).equals(perch.sha, ignoreCase = true)

        if (!validPerch()) {
            if (model.exists()) model.delete()
            val part = File(runtimeDir, perch.name + ".download")
            part.delete()
            val url = URI(
                "https://huggingface.co/tphakala/Perch-v2/resolve/main/perch_v2_no_dft.onnx?download=true"
            ).toURL()
            println("Stridulate v0.3: downloading exact frozen Perch 2.0 model (~413 MB). This happens once.")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "Stridulate-Android-v0.3-Gradle")
            }
            try {
                connection.inputStream.use { input ->
                    part.outputStream().buffered(1024 * 1024).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var copied = 0L
                        var nextReport = 50L * 1024 * 1024
                        while (true) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            if (copied >= nextReport) {
                                println("  downloaded ${copied / (1024 * 1024)} MB...")
                                nextReport += 50L * 1024 * 1024
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            require(part.length() == perch.bytes) {
                "Perch download size ${part.length()} != ${perch.bytes}; delete the .download file and retry."
            }
            require(sha256(part).equals(perch.sha, ignoreCase = true)) {
                part.delete()
                "Perch download SHA-256 mismatch; refusing to package an unverified model."
            }
            if (!part.renameTo(model)) {
                part.copyTo(model, overwrite = true)
                part.delete()
            }
        }
        require(validPerch()) { "Frozen Perch 2.0 verification failed after preparation." }
        println("Stridulate v0.3: frozen J.1 / Perch runtime verified and ready for APK packaging.")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(prepareJ1Runtime)
}

android {
    namespace = "com.pgotta.stridulate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pgotta.stridulate"
        minSdk = 26
        targetSdk = 34
        // Stage-J Android consolidation: frozen J.1 + Perch 2.0, 88 species.
        versionCode = 18
        versionName = "0.3"
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
    // Standalone Android Studio packages may bundle the 413 MB Perch model.
    // Keep ONNX uncompressed so AssetManager can stream it efficiently on first launch.
    androidResources {
        noCompress += "onnx"
        noCompress += "tflite"
    }
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    // Diagnostic shadow model only: exact pre-v0.3 Epoch-19 Stridulate comparison.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
