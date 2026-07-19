import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val versionProperties = Properties().apply {
    val versionFile = rootProject.file("version.properties")
    if (versionFile.isFile) {
        versionFile.inputStream().use(::load)
    }
}

fun configuredValue(name: String): String? = providers.gradleProperty(name).orNull

fun configuredOrEnvironmentValue(propertyName: String, environmentName: String): String? =
    providers.gradleProperty(propertyName)
        .orElse(providers.environmentVariable(environmentName))
        .orNull

val semanticVersion = configuredValue("zenstreamVersion")
    ?: versionProperties.getProperty("version")
    ?: "0.0.1"
val semanticVersionMatch = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(semanticVersion)
    ?: error("zenstreamVersion must use semantic version format X.Y.Z")
val semanticMinor = semanticVersionMatch.groupValues[2].toLong()
val semanticPatch = semanticVersionMatch.groupValues[3].toLong()
require(semanticMinor in 0..999L && semanticPatch in 0..999L) {
    "zenstreamVersion minor and patch components must be below 1000"
}
val semanticVersionCode = semanticVersionMatch.groupValues[1].toLong() * 1_000_000L +
        semanticMinor * 1_000L +
        semanticPatch
require(semanticVersionCode in 1..2_100_000_000L) {
    "zenstreamVersion produces an Android versionCode outside the supported range"
}

val mainVersion = configuredValue("zenstreamMain")
    ?.toIntOrNull()
    ?.takeIf { it >= 0 }
    ?: Regex("\\\"main\\\"\\s*:\\s*(\\d+)")
        .find(rootProject.file(".main-version.json").takeIf { it.isFile }?.readText().orEmpty())
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }
    ?: 0
val releaseBuild = configuredValue("zenstreamRelease") == "true"
val displayVersion = if (releaseBuild) semanticVersion else "$semanticVersion-main.$mainVersion"

val releaseStoreFile =
    configuredOrEnvironmentValue("releaseStoreFile", "ANDROID_RELEASE_STORE_FILE")
val releaseStorePassword =
    configuredOrEnvironmentValue("releaseStorePassword", "ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = configuredOrEnvironmentValue("releaseKeyAlias", "ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword =
    configuredOrEnvironmentValue("releaseKeyPassword", "ANDROID_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val ciDebugStoreFile =
    configuredOrEnvironmentValue("ciDebugStoreFile", "ANDROID_CI_DEBUG_STORE_FILE")
val ciDebugStorePassword =
    configuredOrEnvironmentValue("ciDebugStorePassword", "ANDROID_CI_DEBUG_STORE_PASSWORD")
val ciDebugKeyAlias = configuredOrEnvironmentValue("ciDebugKeyAlias", "ANDROID_CI_DEBUG_KEY_ALIAS")
val ciDebugKeyPassword =
    configuredOrEnvironmentValue("ciDebugKeyPassword", "ANDROID_CI_DEBUG_KEY_PASSWORD")
val hasCiDebugSigning = listOf(
    ciDebugStoreFile,
    ciDebugStorePassword,
    ciDebugKeyAlias,
    ciDebugKeyPassword,
).all { !it.isNullOrBlank() }

if (releaseBuild && !hasReleaseSigning) {
    error("A stable release build requires Android release signing configuration")
}

android {
    namespace = "com.zenstream.zenstreammobile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.zenstream.zenstreammobile"
        minSdk = 24
        targetSdk = 36
        versionCode = semanticVersionCode.toInt()
        versionName = displayVersion
        buildConfigField("String", "ZENSTREAM_VERSION", "\"$displayVersion\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (hasReleaseSigning || hasCiDebugSigning) {
        signingConfigs {
            if (hasCiDebugSigning) {
                create("ciDebug") {
                    storeFile = rootProject.file(requireNotNull(ciDebugStoreFile))
                    storePassword = requireNotNull(ciDebugStorePassword)
                    keyAlias = requireNotNull(ciDebugKeyAlias)
                    keyPassword = requireNotNull(ciDebugKeyPassword)
                }
            }
            if (hasReleaseSigning) {
                create("ciRelease") {
                    storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                    storePassword = requireNotNull(releaseStorePassword)
                    keyAlias = requireNotNull(releaseKeyAlias)
                    keyPassword = requireNotNull(releaseKeyPassword)
                }
            }
        }
    }

    buildTypes {
        debug {
            if (hasCiDebugSigning) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
        release {
            optimization {
                enable = false
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(files("libs/mpv-android-lib-v0.0.3.aar"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
