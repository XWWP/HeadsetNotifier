# 交接说明（给接手的 AI / 开发者）

一个 Android 应用：连接蓝牙音频设备时，朗读新通知。纯 Java + framework API，**没有第三方 UI 库、没有 XML 布局**，所有界面在代码里 build。

- 仓库：https://github.com/XWWP/HeadsetNotifier （公开，默认分支 `main`）
- 应用包名：`com.sakura.airannounce`

## 这台设备上的位置

| 用途 | 路径 |
| --- | --- |
| 源码（Linux 环境） | `/workspace/android-build/airannounce` |
| 源码（Android 侧同一目录） | `/data/local/tmp/eta/android-build/airannounce` |
| 构建脚本 | `/workspace/build.sh` |
| 产物 APK | `/storage/emulated/0/Download/QQ/airannounce-debug.apk` |

两个路径是同一个物理目录（bind mount），不需要复制。

## 构建

```bash
# 必须在 Linux 环境（debian chroot）执行
bash /workspace/build.sh            # 等价于 gradle assembleDebug
```

脚本做三件事：

1. 注册 QEMU binfmt（设备是 arm64，官方 `aapt2` 只有 x86_64 版，靠 qemu 透明执行；重启后注册失效，脚本会重新挂）；
2. 用 JDK 21 + Gradle 8.10.2 编译；
3. 把 `app-debug.apk` 复制到 `Download/QQ/`。

`gradle.properties` 里的 `android.aapt2FromMavenOverride` 指向本机的 x86_64 aapt2，**只对本机有效**，CI 里必须去掉（workflow 已经用 `sed` 删掉）。

安装（`/storage` 有 SELinux 限制，不能直接 `pm install`）：

```bash
cp /storage/emulated/0/Download/QQ/airannounce-debug.apk /data/local/tmp/a.apk
chmod 644 /data/local/tmp/a.apk
pm install -r /data/local/tmp/a.apk
```

## 推送与发布

网络限制（实测）：`github.com` 的 HTTPS 打不开，SSH **22 端口被切断**，只有 `ssh.github.com:443` 稳定可用。因此 `~/.ssh/config` 固定成：

```
Host github.com
  HostName ssh.github.com
  Port 443
  User git
  IdentityFile ~/.ssh/id_ed25519_airannounce
```

凭据是仓库级 Deploy key（**可写**，只对这一个仓库生效）：GitHub → Settings → Deploy keys，标题 `airannounce-push`。换机器接手时，在新机器生成一对 key 并加为 deploy key 即可，不要搬运私钥。

```bash
git push origin main                 # 日常
git tag -a vX.Y.Z -m "X.Y.Z"         # 发版
git push origin vX.Y.Z               # 触发 CI，自动构建并挂到 Release
```

推送偶尔会被切断（Connection closed），**重试 2–3 次通常就过**，不要因此改配置。

## CI 结果怎么确认

GitHub API 从这台设备访问会 403（共享 IP 触顶），网页也打不开。所以 workflow 会把结果写进 `ci-status` 分支：

```bash
git fetch origin ci-status
git show origin/ci-status:ci-status.txt
# status=success / failure, sha, ref, run 链接, apk_sha256
```

`ci-status` 由 `git commit-tree` 构造的孤立提交推送，不碰工作区，每次强制覆盖。

## 版本号

`app/build.gradle.kts` 的 `versionCode` / `versionName` 与 git tag 保持一致。CI 产物是临时 debug keystore 签名，和本机 debug 包签名不同，覆盖安装会冲突，需要先卸载。

## 代码结构

| 文件 | 职责 |
| --- | --- |
| `MainActivity.java` | 全部界面与弹窗（代码构建 UI）、权限引导、Shizuku 通道入口 |
| `NotificationReaderService.java` | 核心：监听/轮询通知、去重、队列、TTS、音频焦点与音量 |
| `AppPickerActivity.java` | 白名单应用选择 |
| `ShizukuChannel.java` | 无 root 时的降级通道（三条 shell 命令） |
| `TextFormatter.java` | 播报文本清洗、验证码逐位展开 |
| `KeepAliveService.java` | 前台保活服务（仅连接耳机时存在） |
| `AnnounceTileService.java` / `BluetoothStateReceiver.java` / `BootReceiver.java` | 快捷磁贴、蓝牙广播、开机 |
| `keepalive/` | root 保活模块（Magisk / KernelSU / APatch），独立打包，不参与 APK 构建 |

打 tag 时 CI 会额外把 `keepalive/` 打成 `airannounce-thaw-<version>.zip` 挂到 Release；模块日志在模块目录 `keepalive.log`。

弹窗统一走 `showMiuixDialog(...)` + `createDialogButton(...)`；样式常量在 `res/values/styles.xml`（窗口透明 + 面板自绘 28dp 圆角，不要改回 windowBackground 画圆角，那会被内层直角白底盖住）。

## 改代码前必须知道的约定

- **关播报要闭嘴**：`MainActivity.syncKeepAlive()` 里包含 `NotificationReaderService.stopEverything()`，所有开关入口都必须走它，否则队列里剩下的条目会继续念。
- **去重看 postTime**：`isNewNotification()` 里「文本相同 **且** postTime 未前进」才算重复；只看文本会吞掉同一人连发的相同消息。
- **焦点分级**：`AUDIOFOCUS_LOSS` 清队列；`TRANSIENT` / `CAN_DUCK` 只停当前这句、保留队列 5 秒后续播。一刀切清队列会让一句导航语音吞掉整批消息。
- **状态刷新只有一个入口**：`buildLayout()` 末尾会调 `updateStatus()`，别在别处手拼状态文本，否则重建后会退回写死的初值。
- **prefs 监听已注册**：快捷磁贴改 `KEY_ENABLED` 会通过监听同步首页开关，改这块注意别造成回环。

## 已知未修（有意保留）

- 通知轮询在主线程调 `getActiveNotifications()`，条目很多时有 ANR 风险（要改线程模型）。
- 耳机短暂断连期间到达的消息不补播（隐私取舍：宁可漏，不念旧消息）。
- 勿扰时段里「开始 = 结束」被当作不生效，界面仍显示该时段。
- 只有蓝牙音频设备会播报，3.5mm / USB-C 有线耳机不播（产品决策未定）。
- 敏感通知的 deep link 在 ColorOS 上被重定向到「应用通知」页，需用户再点「设置通知使用范围」。

## 改动后建议的实测清单

播报相关改动一律需要**真机 + 蓝牙耳机**验证，模拟器测不出来：

1. 连发两条内容完全相同的消息，是否都念；
2. 播报中来电/导航抢焦点，是否立刻闭嘴且之后能把没念的补上；
3. 播报中拔耳机，媒体音量是否回落；
4. 关闭总开关，是否立刻静默（而不是把剩余队列念完）；
5. 磁贴切开关后回首页，开关与状态胶囊是否一致。

界面相关改动至少回归：主界面、五个二级弹窗、应用选择页、高级设置里的 Shizuku 区块。

## 文档索引

| 文件 | 内容 |
| --- | --- |
| `docs/POWER-REVIEW.md` | 功耗与检测策略评审（对象：2.7.4 + keepalive v1.2，commit `79bc560`） |
| `docs/POWER-REVIEW-RESPONSE.md` | 上述评审的处置记录（2.7.5）：逐条采纳/不采纳、理由、尚待真机验证项 |
| `scripts/measure.sh` | 冻结采样脚本：一次跑完输出样本数、实际 vs 设定间隔、frozen=1 占比与模式切换次数，替代人工扒日志手算 |
| `docs/MEASUREMENT-INTERVAL.md` | 5 秒 vs 20 秒、亮屏 vs 息屏四组对照（40 分钟 / 1100+ 样本，全部 0 次冻结），含方法学更正与局限 |

接手时如果要做功耗相关改动，**先读处置记录**，里面写清了哪些提案已经被否掉、为什么否，避免重复讨论。

## 模块与 app 的约定（重要）

`keepalive/service.sh` 与 app 通过两个信号协作，改任何一侧都要同时看另一侧：

- **`files/active`**：app 在 `KeepAliveService.onCreate/onStartCommand` 创建并刷新（mtime 就是"活体信号"），`onDestroy` 删除。**模块只读不删**——模块删掉而 app 还活着，标记不会重建，会退化成永久不保护。
- **`MODE=high` 的退出条件**：前台服务连续 3 次复查失败、或播报开关关闭，模块只把自身置 `low` 并记 `STALE_BLOCK`，等 app 下次拉起刷新 mtime 自动恢复。

改这块最容易出的问题是"以为在保护、其实没有"，所以任何改动都要在真机上 `tail -f keepalive.log` 看一遍节奏。

## 手动重启守护必须进 init 命名空间（踩过坑）

很多工具类 App 的 root shell 跑在**隔离的挂载命名空间**里：`ls /data/data` 只能看到它自己和 GMS 两个条目（本机实测），看不到其他应用的私有目录。后果非常隐蔽：

- 守护脚本照常启动、照常每 20 秒循环、日志正常滚动；
- 但 `[ -f "$FLAG" ]` 恒为假（`$FLAG` 在 `/data/data/com.sakura.airannounce/files/` 下），于是**永远停在 `low` 模式、永远不进 `high`**；
- 日志里没有任何报错，只有一行行"模式=low"。这和"耳机没连好""bt_audio_active 坏了"长得一模一样。

2026-11-06 排查"耳机明明连着却长期 low、累计解冻恒为 0"时，真因就是这个，与 `bt_audio_active` 无关（`dumpsys media.audio_policy` 走 binder，不受挂载命名空间影响，一直判得很准）。

验证与正确启动方式：

```sh
# 1) 确认自己是不是在隔离命名空间：和 init 的 mnt ns 对比
readlink /proc/1/ns/mnt      # init，例如 mnt:[4026531841]
readlink /proc/self/ns/mnt   # 若不同，你就在隔离空间里

# 2) 通过 init 的根看真实文件（隔离空间里最可靠的取证方式）
ls -la /proc/1/root/data/data/com.sakura.airannounce/files/

# 3) 手动重启守护：必须用 nsenter 切进 init 命名空间
nsenter -t 1 -m sh /data/adb/modules/airannounce-thaw/service.sh

# 4) 装完确认新实例落在正确的命名空间里
readlink /proc/$(cat /data/adb/modules/airannounce-thaw/keepalive.pid)/ns/mnt
```

开机时由 KernelSU/Magisk 执行模块 `service.sh`，默认就在正常命名空间，不受影响——**只有手动重启才会踩这个坑**。脚本已内置自检：启动时若看不到 `$DATA` 会记一条明确告警，不要忽略它。