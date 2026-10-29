plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.ogba.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ogba.app"
        minSdk = 26
        targetSdk = 28 // allows exec of the proot/Alpine sandbox from app storage (Termux approach); sideload only
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
                System.getenv("OGBA_VULKAN_PREFIX")?.let { vk ->
                    val ndkDir = System.getenv("ANDROID_NDK_ROOT_29") ?: ""
                    arguments += listOf("-DOGBA_VULKAN=ON", "-DVulkan_INCLUDE_DIR=$vk/include",
                        "-DVulkan_GLSLC_EXECUTABLE=$ndkDir/shader-tools/linux-x86_64/glslc",
                        "-DSPIRV-Headers_DIR=$vk/share/cmake/SPIRV-Headers")
                }
                cppFlags += listOf("-O3", "-std=c++17")
                cFlags += listOf("-O3")
            }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }
    ndkVersion = "29.0.13113456"
    packaging { jniLibs { useLegacyPackaging = true } }  // extract .so so ggml can dlopen CPU/GPU backend variants
    val ksFile = System.getenv("OGBA_KEYSTORE_FILE")?.let { file(it) }
    signingConfigs {
        if (ksFile != null && ksFile.exists()) create("ogba") {
            storeFile = ksFile
            storePassword = System.getenv("OGBA_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("OGBA_KEY_ALIAS") ?: "ogba"
            keyPassword = System.getenv("OGBA_KEYSTORE_PASSWORD")
        }
    }
    val signing = signingConfigs.findByName("ogba") ?: signingConfigs.getByName("debug")
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signing }
        debug { signingConfig = signing }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { disable += "ExpiredTargetSdkVersion" }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.apache.commons:commons-compress:1.26.2")
}
