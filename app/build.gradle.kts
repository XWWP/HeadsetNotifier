plugins {
    id("com.android.application")
}

// CI 用：环境变量指定 keystore 就显式走这个签名配置。
// 不能只依赖 AGP 的"默认 debug keystore"——实测把文件放到 $HOME/.android/debug.keystore
// 之后，CI 产物仍被另一个临时密钥签名（每轮指纹都不同），行为不可控。
// 本地构建不设这个变量，签名方式保持原样。
val explicitKeystore = System.getenv("AIRANNOUNCE_DEBUG_KEYSTORE")

android {
    namespace = "com.sakura.airannounce"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sakura.airannounce"
        minSdk = 26
        targetSdk = 34
        versionCode = 30
        versionName = "2.8.0"
    }

    signingConfigs {
        if (explicitKeystore != null) {
            create("explicitDebug") {
                storeFile = file(explicitKeystore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (explicitKeystore != null) {
                signingConfig = signingConfigs.getByName("explicitDebug")
            }
        }
    }
}

dependencies {
    // Shizuku 降级通道：无 root 设备上用 shell 权限做重绑监听 / standby bucket，
    // 不能解冻 cgroup.freeze，只是兜底
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
