import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.petonboard.app"
    compileSdk = 35
    // 속도(PERF_VEHICLE_SPEED)를 읽기 위한 Android Automotive Car API
    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.petonboard.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"
    }

    // release 서명: 프로젝트 맨 위의 keystore.properties (GitHub에는 올리지 않음)
    val ksFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (ksFile.exists()) {
            val ks = Properties().apply { ksFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            // 에뮬레이터(Pleos Connect)는 앱마켓 심사를 거치지 않은 앱의 화면을 주행 중에 막음.
            // 심사·시연용 APK가 주행 중 화면을 보여 줄 수 있도록 debuggable로 빌드 (앱마켓 배포 때는 false)
            isDebuggable = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // buildConfig: 테스트 줄을 개발(debug) 빌드에서만 보이게 하려고 씀
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    // F4 소리 모델은 압축하지 않아야 바로 메모리에 올릴 수 있음
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    // F4 반려견 소리 감지 (YAMNet, 차 안에서 처리)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
