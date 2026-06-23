import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun configValue(name: String, defaultValue: String): String {
    return (findProperty(name) as String?)
        ?: localProps.getProperty(name)
        ?: defaultValue
}

fun buildConfigString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

fun resString(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

fun secretValue(name: String): String? {
    return (findProperty(name) as String?)
        ?: localProps.getProperty(name)
        ?: System.getenv(name)
}

val appId = configValue("androidApplicationId", "ru.saulinfo.cabinet")
val appName = configValue("androidAppName", "SaulInfo")
val cabinetUrl = configValue("cabinetUrl", "https://example.com/")
val androidAppApiKey = configValue("androidAppApiKey", "")
val allowDomainChange = configValue("allowDomainChange", "false").toBooleanStrictOrNull() ?: false
val debugWebView = configValue("debugWebView", "true").toBooleanStrictOrNull() ?: true

android {
    namespace = "ru.saulinfo.cabinet"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 35
        versionCode = (configValue("versionCode", "1")).toInt()
        versionName = configValue("versionName", "1.0.0")

        buildConfigField("String", "CABINET_URL", buildConfigString(cabinetUrl))
        buildConfigField("String", "APP_DISPLAY_NAME", buildConfigString(appName))
        buildConfigField("String", "ANDROID_APP_API_KEY", buildConfigString(androidAppApiKey))
        buildConfigField("Boolean", "ALLOW_DOMAIN_CHANGE", allowDomainChange.toString())
        resValue("string", "app_name", resString(appName))
    }

    signingConfigs {
        create("release") {
            val storeFilePath = secretValue("SAULINFO_KEYSTORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = secretValue("SAULINFO_KEYSTORE_PASSWORD")
                keyAlias = secretValue("SAULINFO_KEY_ALIAS")
                keyPassword = secretValue("SAULINFO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("Boolean", "WEBVIEW_DEBUGGING", debugWebView.toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
            buildConfigField("Boolean", "WEBVIEW_DEBUGGING", "false")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
}
