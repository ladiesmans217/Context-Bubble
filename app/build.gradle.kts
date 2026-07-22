import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.contextbubble.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.contextbubble.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        val managedBackendUrl = providers.environmentVariable("MANAGED_BACKEND_URL")
            .orElse(providers.gradleProperty("managedBackendUrl"))
            .getOrElse("https://oxrytpcmkqkprqefzkrf.supabase.co/functions/v1/api/")
        require(managedBackendUrl.startsWith("https://") && managedBackendUrl.endsWith("/")) {
            "Managed backend URL must use HTTPS and end with a slash"
        }
        buildConfigField("String", "BACKEND_BASE_URL", "\"${managedBackendUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        val policyPublicKey = providers.environmentVariable("POLICY_SIGNING_PUBLIC_KEY_DER_BASE64")
            .orElse(providers.gradleProperty("policySigningPublicKeyDerBase64"))
            .getOrElse("")
        buildConfigField("String", "POLICY_SIGNING_PUBLIC_KEY_DER_BASE64", "\"${policyPublicKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        val integrityProjectNumber = providers.environmentVariable("PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER")
            .orElse(providers.gradleProperty("playIntegrityCloudProjectNumber"))
            .getOrElse("0")
        buildConfigField("long", "PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER", "${integrityProjectNumber.toLongOrNull() ?: 0L}L")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            applicationIdSuffix = ".play"
            versionNameSuffix = "-play"
            buildConfigField("boolean", "LAB_AUTOMATION", "false")
            buildConfigField("String", "LAB_INVITE_CREDENTIAL", "\"\"")
            resValue("string", "app_name", "Context Bubble")
        }
        create("lab") {
            dimension = "distribution"
            applicationIdSuffix = ".lab"
            versionNameSuffix = "-lab"
            buildConfigField("boolean", "LAB_AUTOMATION", "true")
            val labInvite = providers.environmentVariable("LAB_INVITE_CREDENTIAL")
                .orElse(providers.gradleProperty("labInviteCredential"))
                .getOrElse("")
            buildConfigField("String", "LAB_INVITE_CREDENTIAL", "\"${labInvite.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
            resValue("string", "app_name", "Context Bubble Lab")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "BACKEND_BASE_URL", "\"http://127.0.0.1:8787/\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // Baseline rules must be collected against source class names;
            // release R8 rewrites those rules when packaging the final APK.
            isMinifyEnabled = false
            isShrinkResources = false
        }
        create("performance") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.webrtc.android)
    implementation(libs.google.play.services.auth)
    implementation(libs.google.play.integrity)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register("verifyPlayArtifactIsolation") {
    group = "verification"
    description = "Builds the Play release APK and proves Lab-only automation markers and QUERY_ALL_PACKAGES are absent."
    dependsOn("assemblePlayRelease")
    doLast {
        val apk = layout.buildDirectory.dir("outputs/apk/play/release").get().asFile
            .listFiles()
            ?.singleOrNull { it.extension == "apk" }
            ?: error("Play release APK was not found")
        val forbidden = listOf(
            "App is not in the Lab automation allowlist",
            "android.permission.QUERY_ALL_PACKAGES",
        )
        ZipFile(apk).use { archive ->
            val entries = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
            forbidden.forEach { marker ->
                val ascii = marker.toByteArray(Charsets.UTF_8)
                val utf16 = marker.toByteArray(Charsets.UTF_16LE)
                val containingEntry = entries.firstOrNull { entry ->
                    archive.getInputStream(entry).use { stream ->
                        val bytes = stream.readBytes()
                        bytes.containsSequence(ascii) || bytes.containsSequence(utf16)
                    }
                }
                check(containingEntry == null) {
                    "Play artifact contains forbidden Lab marker '$marker' in ${containingEntry?.name}"
                }
            }
        }
    }
}

fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    outer@ for (start in 0..size - needle.size) {
        for (offset in needle.indices) if (this[start + offset] != needle[offset]) continue@outer
        return true
    }
    return false
}
