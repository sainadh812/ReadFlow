import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
val misakiAbi = if (providers.gradleProperty("emulator").orNull == "true") "x86_64" else "arm64-v8a"
val prepareMisaki by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/prepare_misaki.py", "--abi", misakiAbi)
    inputs.dir("src/main/misaki")
    inputs.file(rootProject.file("scripts/prepare_misaki.py"))
    inputs.file("src/main/assets/licenses/misaki/readflow-provenance.json")
    inputs.property("abi", misakiAbi)
    outputs.file(layout.buildDirectory.file("generated/misaki/jniLibs/$misakiAbi/libreadflow_misaki.so"))
    outputs.file(layout.buildDirectory.file("generated/misaki/jniLibs/$misakiAbi/libreadflow_misaki.build.json"))
}
tasks.named("preBuild").configure { dependsOn(prepareMisaki) }
// Upload credentials belong to the publisher's environment, never the repository.
val uploadKeys = listOf("READFLOW_UPLOAD_STORE_FILE", "READFLOW_UPLOAD_STORE_PASSWORD", "READFLOW_UPLOAD_KEY_ALIAS", "READFLOW_UPLOAD_KEY_PASSWORD")
val uploadValues = uploadKeys.associateWith { providers.environmentVariable(it).orNull }
val privacyPolicyUrl = providers.environmentVariable("READFLOW_PRIVACY_POLICY_URL").orElse("").get()
val parsedPrivacyUrl = runCatching { URI(privacyPolicyUrl) }.getOrNull()
require(privacyPolicyUrl.isEmpty() || (parsedPrivacyUrl?.scheme == "https" && !parsedPrivacyUrl.host.isNullOrBlank() &&
    parsedPrivacyUrl.userInfo == null && privacyPolicyUrl.none { it == '\"' || it == '\\' || it.isWhitespace() })) {
    "READFLOW_PRIVACY_POLICY_URL must be a public HTTPS URL."
}
require(uploadValues.values.all { it.isNullOrBlank() } || uploadValues.values.all { !it.isNullOrBlank() }) {
    "Set all four READFLOW_UPLOAD_* variables, or leave all unset to build an unsigned review bundle."
}
if (providers.gradleProperty("playPublication").orNull == "true") {
    require(uploadValues.values.all { !it.isNullOrBlank() } && privacyPolicyUrl.isNotEmpty()) {
        "Publication builds need all four READFLOW_UPLOAD_* variables and READFLOW_PRIVACY_POLICY_URL. See docs/PLAY_STORE_HANDOFF.md."
    }
}
val prepareReleaseNotices by tasks.registering {
    val runtime = configurations.named("releaseRuntimeClasspath")
    val output = layout.buildDirectory.dir("generated/releaseNotices")
    inputs.files(runtime)
    inputs.file(rootProject.file("scripts/package_dependency_notices.py"))
    outputs.dir(output)
    doLast {
        val inventory = layout.buildDirectory.file("release-runtime-artifacts.tsv").get().asFile
        inventory.parentFile.mkdirs()
        inventory.writeText(runtime.get().resolvedConfiguration.resolvedArtifacts.sortedBy { it.moduleVersion.id.toString() }
            .joinToString("\n") { "${it.moduleVersion.id}\t${it.file.absolutePath}" })
        project.exec {
            commandLine("python3", rootProject.file("scripts/package_dependency_notices.py"), inventory, output.get().asFile)
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(prepareReleaseNotices) }
android {
    namespace = "app.readflow"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    defaultConfig {
        applicationId = "app.readflow"
        minSdk = 35
        targetSdk = 36
        versionCode = 18
        versionName = "0.2.2"
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyPolicyUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release") } }
        ndk { abiFilters += if (providers.gradleProperty("emulator").orNull == "true") "x86_64" else "arm64-v8a" }
    }
    signingConfigs {
        if (!uploadValues[uploadKeys.first()].isNullOrBlank()) create("upload") {
            storeFile = rootProject.file(uploadValues.getValue("READFLOW_UPLOAD_STORE_FILE")!!)
            storePassword = uploadValues.getValue("READFLOW_UPLOAD_STORE_PASSWORD")
            keyAlias = uploadValues.getValue("READFLOW_UPLOAD_KEY_ALIAS")
            keyPassword = uploadValues.getValue("READFLOW_UPLOAD_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") { versionNameSuffix = "-prototype" }
        getByName("release") {
            isDebuggable = false
            // Keep the validated JNI names and reflection paths intact for the first store candidate.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/misaki/jniLibs"))
    sourceSets["release"].assets.srcDir(layout.buildDirectory.dir("generated/releaseNotices"))
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    debugImplementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
