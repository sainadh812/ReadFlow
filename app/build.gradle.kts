plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
android {
    namespace = "app.readflow"
    compileSdk = 35
    buildToolsVersion = "34.0.0"
    defaultConfig {
        applicationId = "app.readflow"
        minSdk = 35
        targetSdk = 35
        versionCode = 7
        versionName = "0.1.6-prototype"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += if (providers.gradleProperty("emulator").orNull == "true") "x86_64" else "arm64-v8a" }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        // Upstream's static AAR still bundles a dynamic ORT for 32-bit x86, which we do not support.
        jniLibs.excludes += setOf("**/x86/**", "**/armeabi-v7a/**")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    adbOptions { timeOutInMs = 120_000 }
    lint { abortOnError = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
