# 耳机通知播报

一个不需要 root、Magisk 或 LSPosed 的 Android App。通过 Android 的通知访问服务读取新通知；检测到蓝牙音频输出设备时，用系统文字转语音播报应用名、标题和正文。

## 使用

1. 用 Android Studio 打开 `airannounce` 目录并构建、安装 App。
2. 打开 App，进入“通知访问设置”，允许“耳机通知播报”读取通知。
3. 返回 App，确认“启用通知播报”已打开，并连接蓝牙耳机。
4. 点“测试语音”确认系统已安装并启用文字转语音引擎。

语音使用系统当前 TTS 引擎和语言。此版本会忽略持续通知、通知组摘要和本 App 自己的通知；同一条通知内容在短时间内重复更新不会重复朗读。通知访问权限授予后，播报内容由本机处理，不会发送到网络。

## root 保活模块（可选）

非 root 也能用。如果系统反复冻结本应用（一加/ColorOS 上表现为播报突然静默），可以刷入 `keepalive/` 里的模块，同样适用于 KernelSU / APatch：

- 安装：从 Release 下载 `airannounce-thaw-*.zip`，在管理器里安装后重启；也可以自己把 `keepalive/` 打包（`module.prop` 放压缩包根目录）。
- 行为：只对 `com.sakura.airannounce` 生效。有耳机时每 2 秒解冻一次，平时低频待机；应用内关掉播报后自动退回待机。
- 排查：日志在 `/data/adb/modules/airannounce-thaw/keepalive.log`（不可写时退到 `/data/local/tmp/airannounce-thaw.log`）。
- 卸载：管理器里移除模块即可，不残留改动。

## 构建

需要 Android Studio、Android SDK Platform 35 和 JDK 17 或更高版本。使用 Android Studio 打开本目录，然后运行 `app` 的 `assembleDebug` Gradle 任务即可生成 APK。

该项目不申请蓝牙、网络或通知发布权限：是否连接蓝牙音频设备通过 Android 音频输出设备列表判断；通知访问权限由用户在系统设置中明确授予。
