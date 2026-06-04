plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.Bible3650.www"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.Bible3650.www"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Credentials come from environment variables or Gradle properties (e.g. in
            // ~/.gradle/gradle.properties) and are never committed. When they are absent,
            // storeFile stays null and release builds remain unsigned so local/CI builds
            // still succeed.
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")
                ?: (project.findProperty("RELEASE_STORE_FILE") as String?)
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    ?: (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    ?: (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
            }
        }
    }

    buildTypes {
        release {
            // #17: Enable R8 minification in release so debug Log.d/Log.v calls are
            // stripped via the rule in proguard-rules.pro. This also shrinks the APK.
            isMinifyEnabled = true

            // Use the real release key when its credentials are configured (CI/release builds
            // set RELEASE_STORE_FILE etc., so storeFile is non-null). Otherwise fall back to the
            // debug key so local/CI builds without those credentials stay installable/testable.
            // Without this branch the release signingConfig was dead config and every release
            // artifact — even a real one with credentials present — was signed with the debug key.
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) releaseSigning
                            else signingConfigs.getByName("debug")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        unitTests.all {
            it.systemProperty("org.sqlite.tmpdir", project.file("../sqlite_tmp").absolutePath)
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
    
    // Audio files are now loaded at runtime from user-selected folders via SAF.
    // No bundled asset packs are required.

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

ksp {
    // Room's KSP processor writes the exported schema JSON files here. The
    // schemas/ directory is committed to version control as migration history;
    // it is NOT added to the main source set so the JSON files are not packaged
    // into the APK as assets.
    arg("room.schemaLocation", "$projectDir/schemas")
}


dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}