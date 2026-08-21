import com.android.build.api.variant.impl.VariantOutputImpl
import com.android.build.gradle.internal.tasks.factory.dependsOn
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.compose)
    alias(libs.plugins.detekt)
}

// Release signing is read from a keystore.properties file at the project root (gitignored, never
// committed). When it's absent — e.g. on CI or an F-Droid/IzzyOnDroid build server that signs the
// APK itself — the release build is simply left unsigned instead of failing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

// Bump this on every published build, and specifically its dotted part: an external source's update
// check reads the version out of the release tag / APK name and stops at the first hyphen, so a new
// "-beta.N" on its own reads as the same version and no update is offered.
val latestVersionName = "1.0.4"

android {
    namespace = "com.looker.droidify"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.omnify.vroot"
        minSdk = 23
        // Spelled out rather than left to default, which is not the harmless omission it looks like:
        // AGP falls back to compileSdk when this is unset, so raising compileSdk to 37 silently moved
        // this to 37 as well, and with it every behaviour Android applies to an app that claims to
        // target its newest release (background limits, foreground service rules, installer
        // restrictions). compileSdk only decides what the code compiles against; this is what the
        // device reads. 36 is exactly what the app has been shipping and running as, so pinning it
        // changes nothing at all, it just stops the number from following a build setting around.
        // Raising it is a deliberate decision with its own testing, not a side effect.
        targetSdk = 36
        versionName = latestVersionName
        // Android's own ordering, invisible to the user: it refuses to install over a build whose
        // versionCode isn't lower, so this has to rise on every published build regardless of what
        // versionName says.
        versionCode = 1005

        testInstrumentationRunner = "com.looker.droidify.TestRunner"
    }

    androidResources.generateLocaleConfig = true

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.generateKotlin", "true")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Signed only when keystore.properties is present (see top of file). Without it the
            // release APK is unsigned — fine for an F-Droid/IzzyOnDroid build that signs on their end.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard.pro",
            )
        }
        // A throwaway build for a specific bug report: identical to release (same optimizations, same
        // signing when a local keystore is present) so it actually reproduces what a real user would
        // see, but installs alongside the real app (own applicationId) and is unmistakably labelled as
        // a test build everywhere its name is shown (see src/canary/res/values/strings.xml) — meant to
        // be handed to one reporter to confirm a fix, then discarded, never publicly distributed.
        create("canary") {
            initWith(getByName("release"))
            applicationIdSuffix = ".canary"
            versionNameSuffix = ".canary"
        }
        // Public pre-release channel: identical to release (same optimizations, same signing when a
        // local keystore is present) so it behaves exactly like the real thing, installs alongside the
        // stable app (own applicationId) so trying it never risks the working install, and is labelled
        // "Omnify Beta" everywhere the app name is shown (see src/beta/res/values/strings.xml) — the
        // only difference from release on purpose, so this build is otherwise trustworthy to distribute
        // and gather feedback on.
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta.5"
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = ".d"
            // Same custom key as release/beta/canary when available, so a debug build's signature
            // still matches the expected fingerprint instead of Android Studio's auto-generated
            // debug keystore.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        all {
            // Only the beta channel's own pre-release tag belongs in VERSION_NAME (see the "beta"
            // build type above): release, canary and debug all track latestVersionName as-is.
            val suffix = if (name == "beta") versionNameSuffix.orEmpty() else ""
            buildConfigField(
                type = "String",
                name = "VERSION_NAME",
                value = "\"v$latestVersionName$suffix\"",
            )
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "/DebugProbesKt.bin",
                "/kotlin/**.kotlin_builtins",
                "/kotlin/**.kotlin_metadata",
                "/META-INF/**.kotlin_module",
                "/META-INF/**.pro",
                "/META-INF/**.version",
                "/META-INF/{AL2.0,LGPL2.1,LICENSE*}",
                "/META-INF/versions/9/previous-**.bin",
            )
        }
    }

    kotlin {
        compilerOptions {
            freeCompilerArgs.addAll("-Xcontext-parameters")
            optIn.add("kotlin.RequiresOptIn")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
                val processor = Runtime.getRuntime().availableProcessors() / 2
                if (processor > 1) it.maxParallelForks = processor
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // output.versionName is the fully-resolved per-variant name (defaultConfig.versionName
            // plus that build type's own versionNameSuffix, e.g. beta's "-beta.2"), so the file name
            // always matches what the build type actually is without repeating that logic here.
            (output as? VariantOutputImpl)?.outputFileName?.set(output.versionName.map { "Omnify-v$it.apk" })
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

val contextParamFiles = listOf("**/extension/Flow.kt", "**/extension/Number.kt")

tasks.withType<Detekt>().configureEach {
    jvmTarget = JavaVersion.VERSION_17.toString()
    exclude(contextParamFiles)
    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
    }
}
tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = JavaVersion.VERSION_17.toString()
    exclude(contextParamFiles)
}

val detektFormat by tasks.registering(Detekt::class) {
    description = "Auto-format Kotlin sources via detekt (ktlint rules)."
    group = "formatting"
    autoCorrect = true
    parallel = true
    buildUponDefaultConfig = true
    ignoreFailures = true
    setSource(files("src/main/kotlin", "src/test/kotlin", "src/androidTest/kotlin"))
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**", "**/resources/**")
    exclude(contextParamFiles)
    jvmTarget = JavaVersion.VERSION_17.toString()
    reports {
        html.required.set(false)
        sarif.required.set(false)
        xml.required.set(false)
        txt.required.set(false)
    }
}

dependencies {
    detektPlugins(libs.detekt.formatting)
    detektPlugins(libs.detekt.compose)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.material)
    implementation(libs.core.ktx)
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.viewModel)
    implementation(libs.recyclerview)
    implementation(libs.sqlite.ktx)

    implementation(libs.image.viewer)
    implementation(libs.bundles.coil)

    implementation(libs.datastore.core)
    implementation(libs.datastore.proto)

    implementation(libs.kotlin.stdlib)
    implementation(libs.datetime)

    implementation(libs.bundles.coroutines)

    implementation(libs.libsu.core)
    implementation(libs.bundles.shizuku)

    implementation(libs.serialization)

    implementation(libs.bundles.ktor)
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)

    // On-device translation engine (optional, user-selectable). Models download on first use.
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    // Local Markdown -> HTML rendering for READMEs on Gitea/Forgejo and GitLab (their render APIs
    // require auth), so the README displays — and translates — without any external service.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)

    implementation(libs.work.ktx)

    // Installs the bundled baseline profile (src/main/baseline-prof.txt) so ART AOT-compiles the
    // hot startup + scrolling paths, removing the cold-start jank inherent to a JIT-only first run.
    implementation(libs.profileinstaller)

    implementation(libs.hilt.core)
    implementation(libs.hilt.android)
    implementation(libs.hilt.ext.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.ext.compiler)

    // Compose dependencies
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.bundles.compose.debug)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.test.unit)
    testImplementation(libs.room.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.test.core)
    testImplementation(libs.test.core.ktx)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.hilt.test)
    testRuntimeOnly(libs.junit.platform)
    testRuntimeOnly(libs.junit.vintage.engine)
    kspTest(libs.hilt.compiler)
    androidTestImplementation(libs.hilt.test)
    androidTestImplementation(libs.room.test)
    androidTestImplementation(libs.bundles.test.android)
    kspAndroidTest(libs.hilt.compiler)

//    debugImplementation(libs.leakcanary)
}

// Every language the app actually has a translated string for, read off res/ itself and handed to
// the code as BuildConfig.DETECTED_LOCALES (the list SettingsViewModel offers in the language
// picker). Reading res is more reliable than keeping a list by hand, which drifts the moment a
// translation lands.
//
// This used to sit inside task("detectAndroidLocals") that preBuild depended on, but that task never
// executed anything: task(name) { } runs its block as configuration, so the scan and the
// buildConfigField call below already ran while the build was being configured, and the task itself
// had no action to perform afterwards. Registering it lazily instead, the usual fix for the
// deprecation, would have been worse than the warning: the block would then run only once the task
// graph was built, far too late for buildConfigField to reach the variant, and the picker would come
// up empty. So it stays where it always effectively ran, without the deprecated
// Project.task(String, Action) or the empty task around it.
val detectedLocales: MutableSet<String> = HashSet()
fileTree("src/main/res").visit {
    if (this.file.path.endsWith("strings.xml") &&
        this.file.canonicalFile.readText().contains("<string")
    ) {
        var languageCode = this.file.parentFile.name.replace("values-", "")
        languageCode = if (languageCode == "values") "en" else languageCode
        detectedLocales.add(languageCode)
    }
}
android.defaultConfig.buildConfigField(
    "String[]",
    "DETECTED_LOCALES",
    "{${detectedLocales.sorted().joinToString(",") { "\"$it\"" }}}",
)
