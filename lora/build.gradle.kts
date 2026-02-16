import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

val llamaCppDir: String? = localProperties.getProperty("llama.cpp.dir")
    ?: System.getenv("LLAMA_CPP_DIR")
val buildFromSource = llamaCppDir != null

android {
    namespace = "com.dark.lora"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    defaultConfig {
        minSdk = 30

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        if (buildFromSource) {
            externalNativeBuild {
                cmake {
                    val qnnSdkDir = localProperties.getProperty("qnn.sdk.dir")
                        ?: System.getenv("QNN_SDK_DIR")
                        ?: ""

                    val hexagonSdkDir = localProperties.getProperty("hexagon.sdk.dir")
                        ?: System.getenv("HEXAGON_SDK_ROOT")
                        ?: ""

                    val hexagonToolsDir = localProperties.getProperty("hexagon.tools.dir")
                        ?: System.getenv("HEXAGON_TOOLS_ROOT")
                        ?: ""

                    val enableHexagon = hexagonSdkDir.isNotEmpty() && hexagonToolsDir.isNotEmpty()

                    val cmakeArgs = mutableListOf(
                        "-DANDROID_STL=c++_shared",
                        "-DLLAMA_CPP_DIR=$llamaCppDir"
                    )

                    if (qnnSdkDir.isNotEmpty()) {
                        cmakeArgs.add("-DQNN_SDK_DIR=$qnnSdkDir")
                    }

                    if (enableHexagon) {
                        cmakeArgs.addAll(listOf(
                            "-DHEXAGON_SDK_ROOT=$hexagonSdkDir",
                            "-DHEXAGON_TOOLS_ROOT=$hexagonToolsDir",
                            "-DGGML_HEXAGON=ON"
                        ))
                    }

                    arguments(*cmakeArgs.toTypedArray())

                    cppFlags("-std=c++17", "-fexceptions", "-frtti")

                    targets("lora")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    if (buildFromSource) {
        externalNativeBuild {
            cmake {
                path("src/main/cpp/CMakeLists.txt")
                version = "3.31.4"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    splits {
        abi {
            isEnable = false
        }
    }

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
            // When building from source, CMake outputs duplicate liblora.so and libc++_shared.so
            pickFirsts += "**/liblora.so"
            pickFirsts += "**/libc++_shared.so"
        }
    }

    buildFeatures {
        prefab = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
