plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Responsibility (Phase 1+): contain the Go/Tailscale integration and the
// Android bridge. Keep this boundary very small. This module hosts the
// gomobile build step and the generated AAR from native/tailscale/go.
//
// Note: the module path is `native/tailscale`; the namespace cannot use
// `native` because it is a Java keyword.
android {
    namespace = "org.hearthlane.tailscale"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        // BuildConfig.DEBUG gates the DNS debug logs in the Go bridge.
        buildConfig = true
    }

    testOptions {
        // Android framework calls return defaults (no "not mocked" crashes) in
        // the plain-JVM unit tests of the bridge module.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Builds the embedded Tailscale AAR from Go via gomobile. Run it standalone
// with: ./native/tailscale/go/build-android.sh
// To build with the isolated patched Go toolchain (netlinkrib experiment):
//   ./gradlew -PgoToolchainRoot=/workspace/go-patched :app:assembleDebug
val goAar = layout.buildDirectory.file("tsembed.aar")
val explodedDir = layout.buildDirectory.dir("tsembed-exploded")
val goClassesJar = explodedDir.map { it.file("classes.jar") }

// The Phase 1 netlinkrib fix (docs/TOOLCHAIN_PATCH.md) is required to run tsnet
// on Android 11+. The isolated patched toolchain at <repo>/go-patched is used
// by default when present so the standard `./gradlew assembleDebug` produces a
// device-working APK. An explicit -PgoToolchainRoot still wins; builds without a
// patched toolchain fall back to the standard Go toolchain (CI-safe, but
// tsnet.Start() will fail with `netlinkrib: permission denied` on Android 11+
// devices).
val defaultPatchedGo = rootProject.layout.projectDirectory.dir("go-patched").asFile
val resolvedGoToolchain = providers.gradleProperty("goToolchainRoot").orNull
    ?: defaultPatchedGo.resolve("bin/go").takeIf { it.exists() }?.let { defaultPatchedGo.absolutePath }
    ?: ""

val goBind by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Tailscale embedded AAR from Go via gomobile."
    workingDir = file("go")
    commandLine("bash", "build-android.sh")
    inputs.dir(layout.projectDirectory.dir("go"))
    inputs.property("goToolchainRoot", resolvedGoToolchain.ifBlank { "default" })
    outputs.file(goAar)
    resolvedGoToolchain.takeIf { it.isNotBlank() }?.let { root ->
        environment("PATCHED_GOROOT", root)
    }
}

// The gomobile AAR is unpacked into jniLibs + a classes jar instead of being
// consumed as a local .aar dependency: AGP rejects direct local .aar
// dependencies when packaging this module's own AAR.
val extractGoAar by tasks.registering {
    group = "build"
    description = "Unpacks the gomobile AAR into jniLibs and a classes jar."
    dependsOn(goBind)
    inputs.file(goAar)
    outputs.dir(explodedDir)
    doLast {
        val dest = explodedDir.get().asFile
        dest.deleteRecursively()
        dest.mkdirs()
        copy {
            from(zipTree(goAar.get().asFile))
            into(dest)
        }
    }
}

android {
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(explodedDir.map { it.dir("jni") })
        }
    }
}

tasks.named("preBuild") {
    dependsOn(extractGoAar)
}

dependencies {
    implementation(project(":core:connectivity"))
    implementation(project(":core:frigate"))
    implementation(libs.kotlinx.coroutines.core)
    // api (not implementation): the generated gomobile binding classes
    // (org.hearthlane.tsembed.*) are part of this module's public surface;
    // the app compiles against them directly (see the 9.2 spike endpoint).
    api(files(goClassesJar) { builtBy(extractGoAar) })
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
