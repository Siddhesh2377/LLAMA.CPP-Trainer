import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

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

        externalNativeBuild {
            cmake {
                val localProperties = Properties()
                val localPropertiesFile = rootProject.file("local.properties")
                if (localPropertiesFile.exists()) {
                    localProperties.load(localPropertiesFile.inputStream())
                }

                val qnnSdkDir = localProperties.getProperty("qnn.sdk.dir")
                    ?: System.getenv("QNN_SDK_DIR")
                    ?: error("QNN_SDK_DIR not found!")

                val llamaCppDir = localProperties.getProperty("llama.cpp.dir")
                    ?: System.getenv("LLAMA_CPP_DIR")
                    ?: error("LLAMA_CPP_DIR not found! Add llama.cpp.dir to local.properties")

                val hexagonSdkDir = localProperties.getProperty("hexagon.sdk.dir")
                    ?: System.getenv("HEXAGON_SDK_ROOT")
                    ?: ""

                val hexagonToolsDir = localProperties.getProperty("hexagon.tools.dir")
                    ?: System.getenv("HEXAGON_TOOLS_ROOT")
                    ?: ""

                val enableHexagon = hexagonSdkDir.isNotEmpty() && hexagonToolsDir.isNotEmpty()

                val cmakeArgs = mutableListOf(
                    "-DANDROID_STL=c++_shared",
                    "-DQNN_SDK_DIR=$qnnSdkDir",
                    "-DLLAMA_CPP_DIR=$llamaCppDir"
                )

                if (enableHexagon) {
                    cmakeArgs.addAll(listOf(
                        "-DHEXAGON_SDK_ROOT=$hexagonSdkDir",
                        "-DHEXAGON_TOOLS_ROOT=$hexagonToolsDir",
                        "-DGGML_HEXAGON=ON"
                    ))
                }

                arguments(*cmakeArgs.toTypedArray())

                cppFlags("-std=c++17", "-fexceptions", "-frtti")

                // Exclude npu_test from CMake's automatic library packaging
                targets("lora")  // Only build the lora library target
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

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.4"
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
        }

        // Exclude the CMake-built executable from packaging
        resources {
            excludes += listOf(
                "**/libnpu_test_exec.so"
            )
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