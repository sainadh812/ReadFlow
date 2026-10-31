Read Assist supplemental runtime dependency notices

These files supplement embedded notices under licenses/dependencies. The component
inventory and exact Maven POMs preserve original upstream authorship and license
declarations for dependencies whose binary artifacts contained no embedded notices.
The full Apache 2.0 text is shared by components mapped to Apache-2.0.txt;
MIT and BSD exceptions are explicitly mapped in provenance.json.

Kotlin COPYRIGHT and NOTICE files retain upstream text, including its original
compiler-distribution description; ReadFlow does not include the Kotlin compiler.
Other upstream notices are reproduced unchanged. Google proprietary SDK terms
are separate from their incorporated open-source notices.

AndroidX DataStore relocates Protobuf Java Lite 4.28.2 (upstream tag v28.2),
confirmed from the published binary RuntimeVersion constants and its upstream
build configuration. Its exact upstream BSD license is protobuf-LICENSE.txt.

Source review on 2026-10-26 checked the eight resolved Media3 1.6.1 modules and
Graphics Path 1.0.1 against their matching source distributions. Their reviewed
sources carry Apache-2.0 notices. See the repository's dated re-audit evidence.
MPL-SOURCE-AVAILABILITY.txt supplies source information for the OkHttp Public
Suffix List and ONNX Runtime's Eigen dependency; original source offers remain.

Publication review still needed:
- The POM inventory establishes declared or inherited top-level license types; it is not a full file-level audit. Reconcile newly changed modules and incorporated third-party code against the final artifact.
- Google Play services / ML Kit terms, their SDK telemetry disclosures and binary third-party notices still require publisher review; they are not relicensed by these Apache notices.
- Confirm final AAB includes all generated dependency notices plus these supplemental notices, model/font/Misaki notices and exact binary hashes. No legal clearance is asserted.

Component license mapping:
androidx.annotation:annotation-experimental:1.4.1 -> Apache-2.0.txt
androidx.appcompat:appcompat-resources:1.6.1 -> Apache-2.0.txt
androidx.appcompat:appcompat:1.6.1 -> Apache-2.0.txt
androidx.arch.core:core-common:2.2.0 -> Apache-2.0.txt
androidx.arch.core:core-runtime:2.2.0 -> Apache-2.0.txt
androidx.autofill:autofill:1.0.0 -> Apache-2.0.txt
androidx.compose.material3:material3-android:1.3.2 -> Apache-2.0.txt
androidx.compose.material:material-icons-core-android:1.7.8 -> Apache-2.0.txt
androidx.compose.material:material-icons-extended-android:1.7.8 -> Apache-2.0.txt
androidx.concurrent:concurrent-futures:1.1.0 -> Apache-2.0.txt
androidx.core:core-ktx:1.13.1 -> Apache-2.0.txt
androidx.core:core:1.13.1 -> Apache-2.0.txt
androidx.cursoradapter:cursoradapter:1.0.0 -> Apache-2.0.txt
androidx.customview:customview-poolingcontainer:1.0.0 -> Apache-2.0.txt
androidx.customview:customview:1.0.0 -> Apache-2.0.txt
androidx.datastore:datastore-android:1.1.4 -> Apache-2.0.txt
androidx.datastore:datastore-core-android:1.1.4 -> Apache-2.0.txt
androidx.datastore:datastore-core-okio-jvm:1.1.4 -> Apache-2.0.txt
androidx.datastore:datastore-preferences-android:1.1.4 -> Apache-2.0.txt
androidx.datastore:datastore-preferences-core-jvm:1.1.4 -> Apache-2.0.txt
androidx.datastore:datastore-preferences-external-protobuf:1.1.4 -> protobuf-LICENSE.txt
androidx.datastore:datastore-preferences-proto:1.1.4 -> Apache-2.0.txt
androidx.drawerlayout:drawerlayout:1.0.0 -> Apache-2.0.txt
androidx.emoji2:emoji2-views-helper:1.4.0 -> Apache-2.0.txt
androidx.emoji2:emoji2:1.4.0 -> Apache-2.0.txt
androidx.exifinterface:exifinterface:1.3.6 -> Apache-2.0.txt
androidx.fragment:fragment:1.3.6 -> Apache-2.0.txt
androidx.graphics:graphics-path:1.0.1 -> Apache-2.0.txt
androidx.interpolator:interpolator:1.0.0 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-common-java8:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-common-jvm:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-livedata-core:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-livedata:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-process:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-runtime-android:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-runtime-compose-android:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-runtime-ktx-android:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-viewmodel-android:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-viewmodel-compose-android:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 -> Apache-2.0.txt
androidx.lifecycle:lifecycle-viewmodel:2.8.7 -> Apache-2.0.txt
androidx.loader:loader:1.0.0 -> Apache-2.0.txt
androidx.media3:media3-common:1.6.1 -> Apache-2.0.txt
androidx.media3:media3-container:1.6.1 -> Apache-2.0.txt
androidx.media3:media3-database:1.6.1 -> Apache-2.0.txt
androidx.media3:media3-datasource:1.6.1 -> Apache-2.0.txt
androidx.media3:media3-decoder:1.6.1 -> Apache-2.0.txt
androidx.media3:media3-exoplayer:1.6.1 -> Apache-2.0.txt
androidx.media3:media3-extractor:1.6.1 -> Apache-2.0.txt
androidx.media3:media3-session:1.6.1 -> Apache-2.0.txt
androidx.media:media:1.7.0 -> Apache-2.0.txt
androidx.resourceinspection:resourceinspection-annotation:1.0.1 -> Apache-2.0.txt
androidx.savedstate:savedstate-ktx:1.2.1 -> Apache-2.0.txt
androidx.savedstate:savedstate:1.2.1 -> Apache-2.0.txt
androidx.startup:startup-runtime:1.1.1 -> Apache-2.0.txt
androidx.tracing:tracing:1.0.0 -> Apache-2.0.txt
androidx.vectordrawable:vectordrawable-animated:1.1.0 -> Apache-2.0.txt
androidx.vectordrawable:vectordrawable:1.1.0 -> Apache-2.0.txt
androidx.versionedparcelable:versionedparcelable:1.1.1 -> Apache-2.0.txt
androidx.viewpager:viewpager:1.0.0 -> Apache-2.0.txt
com.google.firebase:firebase-annotations:16.0.0 -> Apache-2.0.txt
com.google.firebase:firebase-encoders:16.1.0 -> Apache-2.0.txt
com.google.guava:failureaccess:1.0.2 -> Apache-2.0.txt
com.google.guava:guava:33.3.1-android -> Apache-2.0.txt
com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava -> Apache-2.0.txt
com.microsoft.onnxruntime:onnxruntime-android:1.22.0 -> ../onnxruntime-1.22-MIT.txt
com.squareup.okio:okio-jvm:3.6.0 -> Apache-2.0.txt
javax.inject:javax.inject:1 -> Apache-2.0.txt
org.jetbrains.kotlin:kotlin-android-extensions-runtime:1.9.22 -> Apache-2.0.txt
org.jetbrains.kotlin:kotlin-parcelize-runtime:1.9.22 -> Apache-2.0.txt
org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.10 -> Apache-2.0.txt
org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10 -> Apache-2.0.txt
org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> Apache-2.0.txt
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1 -> Apache-2.0.txt
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1 -> Apache-2.0.txt
org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1 -> Apache-2.0.txt
org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.0 -> Apache-2.0.txt
org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.0 -> Apache-2.0.txt
org.jetbrains:annotations:23.0.0 -> Apache-2.0.txt
org.jspecify:jspecify:1.0.0 -> Apache-2.0.txt
