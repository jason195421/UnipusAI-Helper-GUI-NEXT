plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

android {
    namespace = "io.github.unipus.helper"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "io.github.unipus.helper"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "3.9.19-android.2"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets.getByName("main").assets.srcDir("build/generated/audio-assets")
    sourceSets.getByName("androidTest").assets.srcDir("build/generated/audio-test-assets")
}

val prepareNativeAudio by tasks.registering(Exec::class) {
    val python = project.findProperty("pythonExecutable") as? String ?: "python3"
    commandLine(python, rootProject.file("../scripts/prepare_android_audio.py"))
    inputs.files(rootProject.file("../scripts/prepare_android_audio.py"), rootProject.file("../UnipusAI_Helper.py"))
    outputs.dirs(file("build/native-deps"), file("build/generated/audio-assets"), file("build/generated/audio-test-assets"))
}
tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureCMake")) dependsOn(prepareNativeAudio)
}

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython(project.findProperty("pythonExecutable") as? String ?: "python3")
        pip { install("requests==2.32.5") }
    }
    sourceSets {
        getByName("main") { srcDir("build/python-src") }
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
