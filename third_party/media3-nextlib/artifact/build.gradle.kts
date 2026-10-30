plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.anilbeesetti.nextlib.media3ext"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addStaticSourceDirectory(rootProject.file("build/nextlib/source/media3ext/src/main/java").absolutePath)
        variant.sources.jniLibs?.addStaticSourceDirectory(rootProject.file("build/nextlib/native").absolutePath)
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.annotation:annotation:1.10.0")
    compileOnly("com.google.errorprone:error_prone_annotations:2.50.0")
    compileOnly("org.checkerframework:checker-qual:4.3.1")
}
