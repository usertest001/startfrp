import com.android.build.api.dsl.AndroidResources

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "pub.log.startfrp"
    compileSdk = 34

    defaultConfig {
        applicationId = "pub.log.startfrp"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.260125"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // 配置JNI库
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    
    // 配置APK文件名格式
    applicationVariants.all {
        val variantName = name
        outputs.all {
            val apkName = "pub.log.startfrp-${System.currentTimeMillis()}--${variantName}.apk"
            if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                outputFileName = apkName
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(rootProject.extra["android.releaseStoreFile"] as String)
            storePassword = rootProject.extra["android.releaseStorePassword"] as String
            keyAlias = rootProject.extra["android.releaseKeyAlias"] as String
            keyPassword = rootProject.extra["android.releaseKeyPassword"] as String
        }
    }

    // 确保二进制文件不被压缩
    androidResources {
        // 仅不压缩特定文件类型，而不是所有文件
        noCompress.add("so")
        noCompress.add("toml")
        noCompress.add("crt")
        noCompress.add("key")
        noCompress.add("sh")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            // 让debug使用与release相同的签名配置
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // 配置构建变体命名
            applicationIdSuffix = ""
            versionNameSuffix = ""
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    // 配置jniLibs目录
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
    
    // 使用旧的native库打包方式，确保库能正确安装到设备
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation("androidx.core:core:1.12.0")

    // Activity 和 Fragment
    implementation("androidx.fragment:fragment:1.6.1")

    // 生命周期
    implementation("androidx.lifecycle:lifecycle-runtime:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.7.0")
    
    // Shizuku依赖 - 使用最新版本13.1.5
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}