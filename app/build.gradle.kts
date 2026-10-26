import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "me.routerfor.cpa"
    // compileSdk must be >= 30 for AGP 8.x Java 17 sources; targetSdk stays 28 on purpose
    // so the API-28 compatibility behaviours (cleartext loopback + private-dir .so load) apply.
    compileSdk = 34

    defaultConfig {
        applicationId = "me.routerfor.cpa"
        minSdk = 26
        // Personal-use build: the API 28 compatibility mode keeps cleartext loopback HTTP
        // and System.load() of a .so from the private hot-update dir working.
        targetSdk = 28
        versionCode = 4
        versionName = "2.2.0"
        // Shipped update channel: the app reads releases/latest from this repository only.
        buildConfigField("String", "UPDATE_REPO", "\"Heccdod/CPA-Android\"")
        buildConfigField("String", "RULES_URL", "\"https://raw.githubusercontent.com/Heccdod/CPA-Android/main/rules/config.yaml\"")
        ndk {
            // Phones are arm64. The x86_64 kernel is only packaged on request, so the
            // personal-use APK stays lean while an emulator run remains possible:
            //   ./gradlew assembleDebug -PwithEmulatorAbi=true
            abiFilters += if (project.hasProperty("withEmulatorAbi")) {
                listOf("arm64-v8a", "x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }
    }

    // Personal-use build: a private release key would be cpa-release.jks plus a
    // keystore.properties, but this checkout has none, and an unsigned release APK cannot be
    // installed. When the properties file is absent the release build borrows the debug signing
    // config, which AGP materialises on its own — referencing ~/.android/debug.keystore by path
    // instead would break on a clean CI runner where that file does not exist yet.
    val releaseKeystore = rootProject.file("keystore.properties")
    val releaseProps = Properties().apply {
        if (releaseKeystore.exists()) releaseKeystore.inputStream().use { load(it) }
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = rootProject.file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = false
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += listOf("META-INF/*.kotlin_module")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // gomobile output: classes.jar (me.routerfor.cliproxy.cliproxymobile) + jni/arm64-v8a/libgojni.so
    implementation(files("libs/cliproxy.aar"))
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.browser:browser:1.4.0")
    implementation("androidx.webkit:webkit:1.4.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}