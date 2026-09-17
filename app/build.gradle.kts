import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

layout.buildDirectory.set(file("C:/Users/asus/.gradle-build/PrivateTwo/app"))

android {
    namespace = "org.privatetwo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.privatetwo.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        val stunServer = localProperties.getProperty("privatetwo.stun.server", "stun:stun.l.google.com:19302")
        val turnServer = localProperties.getProperty("privatetwo.turn.server", "")
        val turnUser = localProperties.getProperty("privatetwo.turn.username", "")
        val turnPass = localProperties.getProperty("privatetwo.turn.credential", "")
        val signalingUrl = localProperties.getProperty("privatetwo.signaling.url", "ws://10.0.2.2:8088")

        buildConfigField("String", "STUN_SERVER", "\"$stunServer\"")
        buildConfigField("String", "TURN_SERVER", "\"$turnServer\"")
        buildConfigField("String", "TURN_USERNAME", "\"$turnUser\"")
        buildConfigField("String", "TURN_CREDENTIAL", "\"$turnPass\"")
        buildConfigField("String", "DEFAULT_SIGNALING_URL", "\"$signalingUrl\"")
    }

    signingConfigs {
        create("release") {
            val jksFile = file("privatetwo-release.jks")
            if (jksFile.exists()) {
                storeFile = jksFile
                storePassword = "PrivateTwo2026SecureKey"
                keyAlias = "privatetwo"
                keyPassword = "PrivateTwo2026SecureKey"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
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
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Compose UI & Material 3
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    implementation("io.getstream:stream-webrtc-android-compose:1.3.8")

    // Networking / Signaling
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Security & Biometrics
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Coil for Compose
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
