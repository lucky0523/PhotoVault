plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.photovault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.photovault"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    buildFeatures {
        compose = true
    }

    // With Kotlin 2.x the Compose compiler is applied via the
    // org.jetbrains.kotlin.plugin.compose plugin, so kotlinCompilerExtensionVersion
    // is no longer needed.

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Return default values for android.* stubs (e.g. android.util.Log,
            // android.os.Build) instead of throwing "not mocked" in JVM unit tests.
            isReturnDefaultValues = true
            // Robolectric needs the merged manifest/resources to bootstrap a
            // real Context + ContentResolver for ViewModel/MediaStore tests.
            isIncludeAndroidResources = true
        }
    }
}

// With AGP 9 built-in Kotlin, kotlinOptions moves to the top-level kotlin block.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Opt in to the future default where annotations with no explicit target
        // apply to both the constructor parameter and the backing field/property
        // (silences KT-73255 warnings, e.g. Hilt's @ApplicationContext).
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    // Jetpack Compose BOM (maps to Compose 1.10.x, satisfying backdrop 1.0.6)
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Compose Navigation
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.13.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // Process-wide lifecycle owner, used to pause/resume the connection heartbeat
    // when the app goes to background/foreground (power saving).
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")

    // Hilt - Dependency Injection
    implementation("com.google.dagger:hilt-android:2.60")
    ksp("com.google.dagger:hilt-android-compiler:2.60")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")
    // Hilt-generated code references Error Prone annotations (@CanIgnoreReturnValue);
    // expose them on the compile classpath explicitly.
    implementation("com.google.errorprone:error_prone_annotations:2.50.0")

    // Retrofit - Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Room - Local Database
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // WorkManager - Background Tasks
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Hilt WorkManager integration
    implementation("androidx.hilt:hilt-work:1.4.0")
    ksp("androidx.hilt:hilt-compiler:1.4.0")

    // EncryptedSharedPreferences - Secure Storage
    implementation("androidx.security:security-crypto:1.1.0")

    // Liquid Glass (Backdrop) - frosted glass / refraction effects
    implementation("io.github.kyant0:backdrop:1.0.6")
    // Smooth rounded-rectangle shapes used by backdrop lens effects
    // (backdrop declares this as `implementation`, so expose it explicitly).
    implementation("io.github.kyant0:shapes:1.2.0")

    // Coil - Image Loading
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Coil video frame decoding (for video thumbnails)
    implementation("io.coil-kt:coil-video:2.7.0")

    // Media3 (ExoPlayer) - video playback in the full-screen preview
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // DocumentFile - SAF support
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Core KTX
    implementation("androidx.core:core-ktx:1.19.0")

    // EXIF metadata reading (capture time extraction)
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // ZXing embedded - QR code scanning (login server address quick-fill)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Startup - for disabling default WorkManager initializer
    implementation("androidx.startup:startup-runtime:1.1.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Kotest property-based testing (standalone Arb/checkAll, runs inside JUnit4)
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // Robolectric — provides a real Context + ContentResolver (and SQLite for
    // Room in-memory) so FolderDetailViewModel can be exercised in JVM tests
    // with injectable MediaStore results via ShadowContentResolver.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
