import java.util.Properties

val secretsProperties = Properties().apply {
    val secretsPropertiesFile = rootProject.file("secrets.properties")
    if (secretsPropertiesFile.exists()) {
        secretsPropertiesFile.inputStream().use(::load)
    }
}

fun String.escapeForBuildConfig(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

fun readSecret(propertyName: String, envName: String, placeholder: String): String =
    System.getenv(envName)
        ?.takeIf { it.isNotBlank() }
        ?: secretsProperties.getProperty(propertyName, placeholder)

val deepgramApiKey = readSecret(
    propertyName = "deepgramApiKey",
    envName = "DEEPGRAM_API_KEY",
    placeholder = "YOUR_DEEPGRAM_API_KEY_HERE",
)
val humeApiKey = readSecret(
    propertyName = "humeApiKey",
    envName = "HUME_API_KEY",
    placeholder = "YOUR_HUME_API_KEY_HERE",
)
val geminiApiKey = readSecret(
    propertyName = "geminiApiKey",
    envName = "GEMINI_API_KEY",
    placeholder = "YOUR_GEMINI_API_KEY_HERE",
)
val azureSpeechKey = readSecret(
    propertyName = "azureSpeechKey",
    envName = "AZURE_SPEECH_KEY",
    placeholder = "YOUR_AZURE_SPEECH_KEY_HERE",
)
val azureSpeechRegion = readSecret(
    propertyName = "azureSpeechRegion",
    envName = "AZURE_SPEECH_REGION",
    placeholder = "eastus",
)
val auddApiToken = readSecret(
    propertyName = "auddApiToken",
    envName = "AUDD_API_TOKEN",
    placeholder = "YOUR_AUDD_API_TOKEN_HERE",
)
val deepLApiKey = readSecret(
    propertyName = "deepLApiKey",
    envName = "DEEPL_API_KEY",
    placeholder = "YOUR_DEEPL_API_KEY_HERE",
)
val elevenlabsApiKey = readSecret(
    propertyName = "elevenlabsApiKey",
    envName = "ELEVENLABS_API_KEY",
    placeholder = "YOUR_ELEVENLABS_API_KEY_HERE",
)
val anthropicApiKey = readSecret(
    propertyName = "anthropicApiKey",
    envName = "ANTHROPIC_API_KEY",
    placeholder = "YOUR_ANTHROPIC_API_KEY_HERE",
)
val sonioxApiKey = readSecret(
    propertyName = "sonioxApiKey",
    envName = "SONIOX_API_KEY",
    placeholder = "YOUR_SONIOX_API_KEY_HERE",
)
val speechmaticsApiKey = readSecret(
    propertyName = "speechmaticsApiKey",
    envName = "SPEECHMATICS_API_KEY",
    placeholder = "YOUR_SPEECHMATICS_API_KEY_HERE",
)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.talksync"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.talksync"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEEPGRAM_API_KEY", "\"${deepgramApiKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "HUME_API_KEY", "\"${humeApiKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${geminiApiKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "AZURE_SPEECH_KEY", "\"${azureSpeechKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "AZURE_SPEECH_REGION", "\"${azureSpeechRegion.escapeForBuildConfig()}\"")
        buildConfigField("String", "AUDD_API_TOKEN", "\"${auddApiToken.escapeForBuildConfig()}\"")
        buildConfigField("String", "DEEPL_API_KEY", "\"${deepLApiKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${elevenlabsApiKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${anthropicApiKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "SONIOX_API_KEY", "\"${sonioxApiKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "SPEECHMATICS_API_KEY", "\"${speechmaticsApiKey.escapeForBuildConfig()}\"")
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "edition"

    productFlavors {
        create("standard") {
            dimension = "edition"
            buildConfigField("boolean", "OPTIMIZED_VARIANT", "false")
        }
        create("optimized") {
            dimension = "edition"
            applicationIdSuffix = ".optimized"
            versionNameSuffix = "-optimized"
            buildConfigField("boolean", "OPTIMIZED_VARIANT", "true")
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // UVC host capture for webcams; 1.0.13 ships 16 KB aligned arm64 native libraries.
    implementation("com.herohan:UVCAndroid:1.0.13")

    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:4.12.0")
    implementation("org.json:json:20231013")
    implementation("com.github.gkonovalov:android-vad:2.0.4")

    implementation("com.microsoft.cognitiveservices.speech:client-sdk:1.40.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
