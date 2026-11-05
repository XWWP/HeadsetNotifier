plugins {
    id("com.android.application")
}

android {
    namespace = "com.sakura.airannounce"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sakura.airannounce"
        minSdk = 26
        targetSdk = 34
        versionCode = 22
        versionName = "2.7.5"
    }
}

dependencies {
    // Shizuku 降级通道：无 root 设备上用 shell 权限做重绑监听 / standby bucket，
    // 不能解冻 cgroup.freeze，只是兜底
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
