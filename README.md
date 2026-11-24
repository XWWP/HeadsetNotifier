# 耳机通知播报（HeadsetNotifier）

一个不需要 root、Magisk 或 LSPosed 的 Android App。通过通知访问服务读取新通知，检测到蓝牙音频输出时，用系统 TTS 播报应用名、标题和正文。

## 功能

- 只播报指定应用（白名单留空则全部播报）
- 忽略常驻通知、分组摘要和本 App 自己的通知；同一内容短时间内重复更新不重复朗读
- 验证码逐位展开，避免被连读成一串数字
- 语速、播报音量可调；支持勿扰时段
- 快捷磁贴一键开关
- 无 root 也能用；root 环境下可装保活模块，防止被系统冻结后静默
- 装了 Shizuku 会额外用它做监听重绑与 standby bucket 兜底（可选）

## 使用

1. 用 Android Studio 或 Gradle 打开本项目并构建、安装。
2. 打开 App，进入「通知访问设置」，允许「耳机通知播报」读取通知。
3. 返回 App，确认「启用通知播报」已打开，并连接蓝牙耳机。
4. 点「测试语音」确认系统已安装并启用文字转语音引擎。

播报内容由本机处理，不会发送到网络。

## 音量与响度（重要）

App 里的「播报音量」滑块改的是系统媒体音量，播报结束后会尝试恢复原值。

在**蓝牙绝对音量**（绝大多数 TWS 与头戴耳机默认开启）下，耳机侧自己掌握音量：
App 把媒体音量抬到多少，耳机都可能通过 AVRCP 同步回它自己的值，
所以**播报实际有多响，最终由耳机侧音量决定**，这一层应用改不了。

如果觉得播报偏小：

1. 直接在耳机上把音量调大一次（绝对音量下耳机是主控）；
2. 或在开发者选项里关闭「停用绝对音量」，让手机侧重新掌握媒体音量；
3. 或换一个输出电平更高的 TTS 引擎（系统设置 → 文字转语音）。

## root 保活模块（可选）

非 root 也能用。如果系统反复冻结本应用（一加 / ColorOS 上表现为播报突然静默），可以刷入 `keepalive/` 里的模块，同样适用于 KernelSU / APatch：

- 安装：从 Release 下载 `airannounce-thaw-*.zip`，在管理器里安装后重启；也可以自己把 `keepalive/` 打包（`module.prop` 放压缩包根目录）。
- 行为：只对 `com.sakura.airannounce` 生效。有耳机时每 5 秒解冻一次，平时低频待机；应用内关掉播报后自动退回待机。
- 排查：日志在 `/data/adb/modules/airannounce-thaw/keepalive.log`（不可写时退到 `/data/local/tmp/airannounce-thaw.log`）。
- 卸载：管理器里移除模块即可，不残留改动。

## 构建

需要 Android Studio（或 Gradle 8.10.2 + JDK 17 以上）、Android SDK Platform 35。

仓库不含 `gradlew` 与 `gradle/wrapper`：用 Android Studio 打开可直接构建；命令行请自行指定 Gradle，例如 `gradle assembleDebug`。

## 权限

该项目不申请网络权限，也不申请传统蓝牙配对权限：是否连接蓝牙音频设备通过 Android 音频输出设备列表判断。

- `BLUETOOTH_CONNECT`：Android 12+ 读取蓝牙连接状态与设备名
- `POST_NOTIFICATIONS`：前台服务通知与监听掉线提醒
- `QUERY_ALL_PACKAGES`：应用白名单选择器需要列出已安装应用
- 通知访问权限由用户在系统设置中明确授予

## 版本与发布

当前 app `2.9.3 (43)`，保活模块 `v1.13 (14)`，`minSdk 26` / `targetSdk 34` / `compileSdk 35`。

构建产物与校验值发布在 [Releases](https://github.com/XWWP/HeadsetNotifier/releases)：`app-debug.apk`、`apk.sha256`、`airannounce-thaw-*.zip`。
