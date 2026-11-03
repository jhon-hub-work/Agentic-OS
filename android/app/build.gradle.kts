import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release key = the PC's Android debug key, kept forever (the phone already trusts it, so updates install over the
// top). Lives outside the repo: D:/Keys/agentic-os (or the file named in AOS_KEYSTORE_PROPS). No file, no release signing.
val releaseKey = Properties().apply {
    val f = file(System.getenv("AOS_KEYSTORE_PROPS") ?: "D:/Keys/agentic-os/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.jhon.agenticos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jhon.agenticos"
        minSdk = 33 // OnBackInvokedDispatcher
        targetSdk = 36
        versionCode = 2026110305  // yyyyMMddNN: always grows, readable at a glance
        versionName = "1.1.0"
    }

    signingConfigs {
        if (releaseKey.isNotEmpty()) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        // what lives on the phone: shrunk, not debuggable, signed with the kept key
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // everything experimental installs beside it as its own app, so tests never touch his real Home and data
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")  // Android's org.json is a stub in local unit tests
}

// ADR-001: :core (chat alerts, mic, send queue) must never load UI code, so a home-screen crash can't take it down.
// These files run in :core; they may not touch the ui package, WebView, widgets or MainActivity's code.
val checkCoreIsolation by tasks.registering {
    val core = fileTree("src/main/java/com/jhon/agenticos") { include("core/**", "ChatListener.kt", "ListenService.kt", "App.kt") }
    inputs.files(core)
    doLast {
        val banned = Regex("""com\.jhon\.agenticos\.ui|android\.webkit|android\.widget|MainActivity(::|\.)""")
        val bad = core.files.filter { banned.containsMatchIn(it.readText()) }
        if (bad.isNotEmpty()) throw GradleException(":core must not use UI code: " + bad.joinToString { it.name })
    }
}
tasks.named("preBuild") { dependsOn(checkCoreIsolation) }
