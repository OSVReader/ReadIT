import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val releaseSigningProperties = Properties().apply {
    val signingFile = rootProject.file("signing.properties")
    if (signingFile.isFile) {
        signingFile.inputStream().use { load(it) }
    }
}

fun releaseSigningValue(propertyName: String, environmentName: String): String? =
    (releaseSigningProperties.getProperty(propertyName)
        ?: providers.environmentVariable(environmentName).orNull)
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSigningValue("storeFile", "READIT_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "READIT_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "READIT_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "READIT_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null }

android {
    namespace = "com.example.booklibrary"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "io.readit.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8

        // Required for Readium when minSdk < 26
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions { jvmTarget = "1.8" }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abi = output.getFilter(com.android.build.api.variant.FilterConfiguration.FilterType.ABI.name) ?: "universal"
            output.outputFileName = "readit-${variant.versionName}-${abi}-${variant.buildType.name}.apk"
        }
    }
}

dependencies {
    // Sherpa-ONNX (offline TTS engine -- Piper/VITS/Kokoro)
    implementation(files("libs/sherpa-onnx.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager (2.11.0 requires minSdk 23)
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.6")

    // Desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // ===== Readium Kotlin Toolkit =====
    val readiumVersion = "3.1.0"
    implementation("org.readium.kotlin-toolkit:readium-shared:$readiumVersion")
    implementation("org.readium.kotlin-toolkit:readium-streamer:$readiumVersion")
    implementation("org.readium.kotlin-toolkit:readium-navigator:$readiumVersion")

    // Readium TTS Navigator module
    implementation("org.readium.kotlin-toolkit:readium-navigator-media:${readiumVersion}")
    implementation("org.readium.kotlin-toolkit:readium-navigator-media-tts:${readiumVersion}")

    // Media session for TTS notification / lock screen controls
    implementation("androidx.media:media:1.7.0")

    // Apache Commons Compress (for extracting tar.bz2 model archives on Android)
    implementation("org.apache.commons:commons-compress:1.26.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
