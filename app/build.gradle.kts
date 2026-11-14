plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.duplicatecleaner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.duplicatecleaner"
        minSdk = 26
        targetSdk = 34
        // Play Store needs a higher versionCode for every upload; CI uses the run number.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"

        // AdMob IDs. Release builds read real IDs from the environment (GitHub secrets);
        // without them, Google's public TEST IDs are used so you never ship or click real ads by accident.
        // Public URL of the privacy policy, shown in Settings (leave empty to hide the row).
        buildConfigField("String", "PRIVACY_URL", "\"${System.getenv("PRIVACY_URL") ?: ""}\"")

        manifestPlaceholders["admobAppId"] =
            System.getenv("ADMOB_APP_ID") ?: "ca-app-pub-3940256099942544~3347511713"
        buildConfigField(
            "String", "ADMOB_BANNER_ID",
            "\"${System.getenv("ADMOB_BANNER_ID") ?: "ca-app-pub-3940256099942544/6300978111"}\""
        )
        buildConfigField(
            "String", "ADMOB_REWARDED_ID",
            "\"${System.getenv("ADMOB_REWARDED_ID") ?: "ca-app-pub-3940256099942544/5224354917"}\""
        )
    }

    // Release signing comes from environment variables set by the GitHub workflow.
    // Without them, the release build is left unsigned.
    val keystorePath = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (!keystorePath.isNullOrEmpty()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds always use Google's test ads.
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
            buildConfigField("String", "ADMOB_REWARDED_ID", "\"ca-app-pub-3940256099942544/5224354917\"")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")
    implementation("com.android.billingclient:billing-ktx:7.1.1")
    implementation("com.google.android.gms:play-services-ads:23.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
