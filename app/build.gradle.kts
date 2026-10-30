import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val usbDevelopment = providers.gradleProperty("dshUsbDevelopment").orNull == "true"

android {
    namespace = "com.dsh.mobile"
    compileSdk = 36

    defaultConfig {
        ndk { abiFilters += (providers.gradleProperty("dshAbis").orNull
            ?: if (providers.gradleProperty("dshUsbDevelopment").orNull == "true") "arm64-v8a" else "arm64-v8a,armeabi-v7a")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() } }

        applicationId = "com.dsh.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.0-alpha.5"
        buildConfigField("boolean", "USB_DEVELOPMENT", usbDevelopment.toString())
        // Inspect the existing route without switching to isolated USB profiles.
        buildConfigField("boolean", "WEB_INSPECTION", (usbDevelopment ||
            providers.gradleProperty("dshWebInspection").orNull == "true").toString())
    }

    signingConfigs {
        create("release") {
            // Debug-signed release so the APK installs without a keystore ceremony.
            // A real distribution key can replace this later without touching code.
            // Overridable from an untracked keystore.properties for real releases.
            val keystoreProps = rootProject.file("keystore.properties")
            if (keystoreProps.exists()) {
                val props = Properties()
                keystoreProps.inputStream().use { stream -> props.load(stream) }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            } else {
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            // Benchmark the USB route with release compilation/profile behavior.
            isDebuggable = usbDevelopment && providers.gradleProperty("dshBenchmark").orNull != "true"
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // The island payload tests exercise the *real* org.json behaviour, so
            // the stub from android.jar must be replaced by a working
            // implementation on the unit-test classpath (see dependencies below).
            // Without this, every JSONObject.put throws "not mocked".
            isIncludeAndroidResources = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)

    // QR pairing: CameraX preview + ML Kit barcode (bundled model, no Play services).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)

    // Compose versions are intentionally NOT pinned with a BOM here.
    //
    // Miuix 0.8.8 is built on Compose Multiplatform 1.10.3, whose Android
    // artifact maps onto androidx.compose.foundation 1.10.5. Adding an androidx
    // compose BOM would pin a second, competing foundation/runtime version and
    // produce mixed-version Compose at runtime (the classic
    // "NoSuchMethodError in Composer" crash). Letting Miuix's own transitive
    // graph define Compose keeps foundation, runtime, ui and animation on one
    // consistent version set.
    implementation(libs.miuix)
    implementation(libs.miuix.icons)

    // Compose tooling for previews in debug builds only.
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation(libs.okhttp)
    implementation(files("libs/tailscale-1.104.2.aar"))
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation(libs.kotlinx.serialization.json)

    // EventReducer is a pure function, so its tests need nothing but JUnit.
    testImplementation("junit:junit:4.13.2")

    // android.jar ships org.json as throwing stubs, so the island payload test
    // needs a real implementation on the unit-test classpath. This is the same
    // org.json API the Android framework exposes at runtime.
    testImplementation("org.json:json:20240303")
}
