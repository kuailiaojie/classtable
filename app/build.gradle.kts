plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    id("kotlin-parcelize")
}

android {
    namespace = "com.kxin.classtable"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kxin.classtable"
        minSdk = 26
        targetSdk = 36
        versionCode = 55
        versionName = "2.0.0-rc3"

        // UI 文案与资源只有中文/英文:去掉依赖库里的其它语言资源,减小安装包
        resourceConfigurations += listOf("zh", "zh-rCN", "en")

        // Firebase 反代地址(大陆访问入口)。换部署站点时只改这一行。
        buildConfigField(
            "String",
            "FIREBASE_PROXY_URL",
            "\"https://classtablek.netlify.app/.netlify/functions/proxy\"",
        )

        // Netlify 站点根:适配器 bundle 等静态资源(<site>/warehouse/bundle.json)。
        buildConfigField(
            "String",
            "SITE_BASE_URL",
            "\"https://classtablek.netlify.app\"",
        )

        // uapis.cn 天气接口的 key(日视图顶部的当前天气)。留空即走访客额度 ——
        // 两者返回的字段一致,填了只是为了走自己的额度;失效时代码会自动降级。
        buildConfigField(
            "String",
            "WEATHER_API_KEY",
            "\"uapi-ywpozu2v7Bzo82vrK3TRcnVUL5vxuJb8AatC-27t\"",
        )
    }

    // CI 签名:配置 KEYSTORE_FILE 等环境变量时启用(未配置时 release 产出未签名 APK,本地构建不受影响)
    signingConfigs {
        if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
            create("release") {
                storeFile = file(System.getenv("KEYSTORE_FILE"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 安装包瘦身:R8 混淆 + 资源压缩 + 只打手机常用 ABI。
            // 注意:JavascriptInterface 方法是按名字反射调用的,keep 规则见 proguard-rules.pro。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // 按 CPU 架构拆包:32 位(armeabi-v7a)与 64 位(arm64-v8a)各出一个安装包 ——
    // 每台设备只用下自己那份 native 库,体积小一截;应用内更新据此只下发匹配本机架构的包。
    //   - x86 / x86_64 保留给模拟器(没有它们,调试版就跑不了模拟器);
    //   - 额外的通吃包(isUniversalApk)给「不带架构信息」的老版本升级用:
    //     老包先升到通吃包,之后每次更新就会认领自己架构的那一个。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/*.version",
                "DebugProbesKt.bin",
                "kotlin/**",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kapt {
    correctErrorTypes = true
}

// CI / 本地无外网时,Crashlytics 的符号上传会连不上 firebasecrashlyticssymbols.googleapis.com
// 而让整个 assembleRelease 失败(实测插件自身对 -PfirebaseCrashlyticsMappingFileUploadEnabled
// 不生效)。这里显式按该属性关掉上传任务,保证没有外网也能产出 release APK。
if (providers.gradleProperty("firebaseCrashlyticsMappingFileUploadEnabled").orNull == "false") {
    tasks.matching { it.name.startsWith("uploadCrashlyticsMappingFile") }.configureEach {
        enabled = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.messaging)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    // 农历/节气:日历周条下的小字(纯 Java,无反射,可在 Android 直接使用)
    implementation(libs.lunar)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}
