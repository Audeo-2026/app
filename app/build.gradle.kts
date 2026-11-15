import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** Audeo 서버 주소와 키는 local.properties(커밋 안 됨) 또는 환경 변수에서 읽습니다. */
val localProps = Properties().apply {
    // Properties.load(InputStream)은 ISO-8859-1로 읽어서 한글이 깨집니다. UTF-8 Reader로 읽어요.
    rootProject.file("local.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
}

fun localOrEnv(key: String, env: String, default: String = ""): String =
    localProps.getProperty(key) ?: System.getenv(env) ?: default

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.audeo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.audeo.app"
        // Pleos Connect는 Android 14(API 34) 기반입니다.
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Pleos Connect 에뮬레이터 이미지(Mac arm64, Windows·Linux x86_64)에 필요한 것만 넣어 APK 크기를 줄인다.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        // Audeo 서버(v1). 초대 코드로 차량 기기를 등록하고, 받은 기기 토큰으로 나머지 API를 부릅니다.
        val server = mapOf(
            "AUDEO_API_BASE_URL" to localOrEnv("audeo.api.baseUrl", "AUDEO_API_BASE_URL", "https://audeo.mosslab.dev/api/v1/"),
            "AUDEO_INVITE_CODE" to localOrEnv("audeo.inviteCode", "AUDEO_INVITE_CODE", localProps.getProperty("audeo.api.key") ?: "audeo"),
            "AUDEO_DRIVER_NAME" to localOrEnv("audeo.driver.name", "AUDEO_DRIVER_NAME", "운전자"),
            "AUDEO_DRIVER_SHORT_NAME" to localOrEnv("audeo.driver.shortName", "AUDEO_DRIVER_SHORT_NAME"),
            "AUDEO_DRIVER_PHONE" to localOrEnv("audeo.driver.phone", "AUDEO_DRIVER_PHONE"),
            "AUDEO_VEHICLE_LABEL" to localOrEnv("audeo.vehicle.label", "AUDEO_VEHICLE_LABEL", "Pleos Connect"),
            "AUDEO_PLATE_NUMBER" to localOrEnv("audeo.vehicle.plate", "AUDEO_PLATE_NUMBER"),
        )
        server.forEach { (name, value) -> buildConfigField("String", name, "\"${value.replace("\"", "\\\"")}\"") }
    }

    // 제출용 release 서명. local.properties(audeo.release.*) 또는 환경 변수에 키가 없으면 debug 키로 서명해서라도 설치되게 한다.
    val releaseStore = localOrEnv("audeo.release.storeFile", "AUDEO_RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStore.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = localOrEnv("audeo.release.storePassword", "AUDEO_RELEASE_STORE_PASSWORD")
                keyAlias = localOrEnv("audeo.release.keyAlias", "AUDEO_RELEASE_KEY_ALIAS", "audeo")
                keyPassword = localOrEnv("audeo.release.keyPassword", "AUDEO_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.bundles.pleos.sdk)
    implementation(libs.okhttp)
    implementation(libs.webrtc)
    implementation(libs.maplibre)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
