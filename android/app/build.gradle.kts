plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.tgwsproxy.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.tgwsproxy.mobile"
        minSdk = 26
        targetSdk = 35
        // Версия растёт сама: CI передаёт номер запуска как -PciBuild (100 + номер запуска),
        // а имя версии выглядит как 0.6.<номер запуска>. Базу 0.6 меняйте здесь вручную,
        // когда нужна новая «большая» версия. Эти же константы читает приложение (BuildConfig).
        val versionBase = "0.6"
        val codeOffset = 100
        val ciBuild = (project.findProperty("ciBuild") as String?)?.toIntOrNull()
        versionCode = ciBuild ?: 6
        versionName = if (ciBuild != null) "$versionBase.${ciBuild - codeOffset}" else "$versionBase.0"
        buildConfigField("String", "VERSION_BASE", "\"$versionBase\"")
        buildConfigField("int", "CODE_OFFSET", "$codeOffset")
    }

    val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    val hasReleaseKey = keystorePath != null && File(keystorePath).exists()

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = File(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            // Без постоянного ключа подпись каждый раз новая, и обновление поверх не встанет.
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            // Код Worker лежит в docs/: приложение берёт его оттуда же и загружает в Cloudflare.
            assets.srcDir("../../docs")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Вход через Google (Credential Manager).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
