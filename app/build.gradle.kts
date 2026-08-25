import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.piercingxx.xxclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piercingxx.xxclock"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Release signing is resolved from (in order):
    //   1. A gitignored keystore.properties at the repo root
    //      (storeFile / storePassword / keyAlias / keyPassword), or
    //   2. Environment variables XXCLOCK_STORE_FILE / XXCLOCK_STORE_PASSWORD /
    //      XXCLOCK_KEY_ALIAS / XXCLOCK_KEY_PASSWORD.
    // If neither is present the release build type stays unsigned (build an
    // unsigned APK and sign it yourself, or use the debug build for testing).
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }

    fun signingValue(propKey: String, envKey: String): String? =
        keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

    val releaseStoreFile = signingValue("storeFile", "XXCLOCK_STORE_FILE")
    val releaseStorePassword = signingValue("storePassword", "XXCLOCK_STORE_PASSWORD")
    val releaseKeyAlias = signingValue("keyAlias", "XXCLOCK_KEY_ALIAS")
    val releaseKeyPassword = signingValue("keyPassword", "XXCLOCK_KEY_PASSWORD")
    val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        // Let JVM unit tests instantiate android.jar stubs (BroadcastReceiver's
        // no-arg constructor) instead of throwing "Stub!" — same convention as
        // TxxT's theme-sync tests. Behavior still comes through injected seams.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
}
