plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

android {
    namespace = "io.github.unipus.helper"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.unipus.helper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "3.9.19-android.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
