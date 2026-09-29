plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Hearthlane environment base domain for the transparent connection strategy.
// The Frigate and Relay endpoints are derived from it (frigate.hearthlane.* and
// relay.hearthlane.*) behind the Nginx Proxy Manager border. Overridable at
// build time:
//   ./gradlew -Phearthlane.baseDomain=hearthlane.omni.corp :app:assembleDebug
val hearthlaneBaseDomain =
    (project.findProperty("hearthlane.baseDomain") as String?) ?: "hearthlane.omni.corp"

android {
    namespace = "org.hearthlane"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 5
        versionName = "0.1.0"

        // Default environment for the shared Frigate/relay base domain. The
        // app points PROD and UAT at the same base domain (Frigate is shared);
        // only the relay service label differs per flavor below.
        buildConfigField("String", "HEARTHLANE_BASE_DOMAIN", "\"$hearthlaneBaseDomain\"")

        manifestPlaceholders["appLabel"] = "Hearthlane"
    }

    // One flavor dimension: the deployment environment. PROD and UAT are two
    // fully independent apps (own applicationId => own sandbox) that differ
    // only in the relay endpoint they consume and in the visible identity.
    flavorDimensions += "environment"
    productFlavors {
        create("prod") {
            dimension = "environment"
            applicationId = "org.hearthlane"
            manifestPlaceholders["appLabel"] = "Hearthlane"
            // Prod relay lives under the historical `relay.<domain>` label.
            buildConfigField("String", "HEARTHLANE_RELAY_SUBDOMAIN", "\"relay\"")
        }
        create("uat") {
            dimension = "environment"
            applicationId = "org.hearthlane.uat"
            versionNameSuffix = "-uat"
            manifestPlaceholders["appLabel"] = "Hearthlane UAT"
            // UAT consumes a dedicated relay on the same base domain; Frigate
            // stays shared (both flavors resolve it from the base domain).
            buildConfigField("String", "HEARTHLANE_RELAY_SUBDOMAIN", "\"relay-uat\"")
        }
    }

    signingConfigs {
        create("release") {
            // Signing credentials are provided externally; they are never
            // committed. Supports Gradle project properties (-P) and environment
            // variables so CI and local release builds can use the mechanism
            // that fits the environment. For Play Store, this key is the upload
            // key; Google re-signs the AAB with the app signing key.
            val storeFilePath = providers.gradleProperty("RELEASE_STORE_FILE")
                .orElse(providers.environmentVariable("RELEASE_STORE_FILE"))
                .orNull
            val storePwd = providers.gradleProperty("RELEASE_STORE_PASSWORD")
                .orElse(providers.environmentVariable("RELEASE_STORE_PASSWORD"))
                .orNull
            val alias = providers.gradleProperty("RELEASE_KEY_ALIAS")
                .orElse(providers.environmentVariable("RELEASE_KEY_ALIAS"))
                .orNull
            val keyPwd = providers.gradleProperty("RELEASE_KEY_PASSWORD")
                .orElse(providers.environmentVariable("RELEASE_KEY_PASSWORD"))
                .orNull
            storeFile = storeFilePath?.let { file(it) }
            storePassword = storePwd
            keyAlias = alias
            keyPassword = keyPwd
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            // R8/ProGuard is intentionally disabled for V1. The gomobile native
            // bridge and the Tailscale Go code use reflection/JNI patterns that
            // have not been validated against obfuscation; a broken release
            // build is a worse outcome than a larger APK/AAB. Revisit only after
            // testing a full ProGuard/R8 configuration on a physical device.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        // Allow android.util.Log calls to be no-ops in JVM unit tests.
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

// Release tasks validate signing credentials early so the failure is explicit
// and close to the command that triggered it. Debug builds are unaffected when
// the release properties are absent. With product flavors the release tasks are
// per variant (assembleProdRelease, assembleUatRelease, ...), so the guard
// matches every task that packages or signs a *Release variant.
tasks.configureEach {
    val isReleasePackaging =
        name.contains("Release") &&
            (name.startsWith("assemble") || name.startsWith("bundle") ||
                name.startsWith("package") || name.startsWith("sign"))
    if (isReleasePackaging) {
        doFirst {
            val cfg = android.signingConfigs.getByName("release")
            require(cfg.storeFile != null && cfg.storeFile!!.exists()) {
                "RELEASE_STORE_FILE must point to an existing keystore file for release builds. " +
                    "See docs/RELEASE.md for the signing setup."
            }
            require(!cfg.storePassword.isNullOrBlank()) {
                "RELEASE_STORE_PASSWORD must be set for release builds. " +
                    "See docs/RELEASE.md for the signing setup."
            }
            require(!cfg.keyAlias.isNullOrBlank()) {
                "RELEASE_KEY_ALIAS must be set for release builds. " +
                    "See docs/RELEASE.md for the signing setup."
            }
            require(!cfg.keyPassword.isNullOrBlank()) {
                "RELEASE_KEY_PASSWORD must be set for release builds. " +
                    "See docs/RELEASE.md for the signing setup."
            }
        }
    }
}

// LiveViewTest drives Compose through ActivityScenario, which resolves
// androidx.activity.ComponentActivity against the variant's app manifest. The
// compose test manifest (debugImplementation of ui-test-manifest) injects that
// activity into the debug manifest, but the release manifest never contains
// it, so the Robolectric launch cannot resolve it on release. The Compose
// contract is fully covered by the debug unit tests; release runs the
// non-UI suites. With product flavors the release unit-test tasks are per
// variant (testProdReleaseUnitTest, testUatReleaseUnitTest, ...).
tasks.configureEach {
    val isReleaseUnitTest =
        (this as? org.gradle.api.tasks.testing.Test) != null &&
            name.startsWith("test") && name.endsWith("ReleaseUnitTest")
    if (isReleaseUnitTest) {
        (this as org.gradle.api.tasks.testing.Test).filter {
            excludeTestsMatching("org.hearthlane.ui.LiveViewTest")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:connectivity"))
    implementation(project(":core:frigate"))
    implementation(project(":core:playback"))
    implementation(project(":core:relay"))
    implementation(project(":native:tailscale"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.osmdroid)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // debugImplementation (not testImplementation) so the merged DEBUG app
    // manifest declares androidx.activity.ComponentActivity for Robolectric's
    // ActivityScenario; the release manifest never carries it (see the
    // testReleaseUnitTest exclusion above).
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
