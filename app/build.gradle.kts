import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.cihmine.napalarm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cihmine.napalarm"
        // Pleos Connect 에뮬레이터 이미지가 API 34
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }


    signingConfigs {
        // 서명 키는 저장소 밖에 둔다(개발자 키 공용). 위치는 -Pnapalarm.signing=경로 로 바꿀 수 있고,
        // 파일이 없으면 release 는 서명 없이 만들어진다(설치용 APK 아님).
        val props = file(
            providers.gradleProperty("napalarm.signing")
                .getOrElse("${System.getProperty("user.home")}/.android-keys/carvoicegame.properties"),
        )
        if (props.exists()) {
            val p = Properties().apply { props.inputStream().use(::load) }
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    // 실제 기어 상태
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
