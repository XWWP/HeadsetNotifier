# 自检报告（2026-11-06 11:30）

版本：app 2.7.8 / versionCode 25，模块 v1.6 / versionCode 7
设备：ColorOS / Android 15

## 一、当前状态（全部实测）

| 项目 | 结果 | 证据 |
|---|---|---|
| 通知监听绑定 | ✅ | `Live notification listeners` 含 sakura |
| 敏感通知权限 | ✅ allow | `cmd appops get ... RECEIVE_SENSITIVE_NOTIFICATIONS` = allow |
| 播报开关 | ✅ 开 | `announcements_enabled=true` |
| 白名单 | ✅ 含 QQ | `allowed_packages=...com.tencent.mobileqq...`（11 个应用） |
| 蓝牙音频 | ✅ 已连接 | `BT A2DP Out @18:5C:A1:52:36:BC` |
| 前台服务 | ✅ | `isForeground=true types=0x10`，`adj=200` |
| 电池优化 | ✅ 已豁免 | `cmd deviceidle whitelist` 含本应用 |
| POST_NOTIFICATIONS | ✅ granted | `granted=true` |
| TTS 引擎 | ✅ 可发声 | `com.oplus.ttsaccessibilityengine`，测试语音有播放记录 |
| 模块守护 | ✅ 高频 | `模式=high`，`累计解冻` 正常增长 |

## 二、隐患清单（按优先级）

### P0-1 重装/升级 APK 后通知监听不会自动重绑

**现象**：三次安装（2.7.6 / 2.7.7 / 2.7.8）后，`Live notification listeners` 里都不再出现本应用，
`NotificationReaderService` 显示 `app=null`。授权字符串本身是好的，只是没有建立绑定。

**影响**：app 收不到任何通知 → 播报完全静默，界面上没有任何告警。

**当前处理**：每次都要手动执行
`cmd notification disallow_listener ... && cmd notification allow_listener ...` 才恢复。

**建议**：
1. 模块在拉起应用后增加一步绑定检查：`dumpsys notification` 里没有本应用就执行
   `cmd notification allow_listener`（root 可做，幂等）；
2. app 内把"监听未连接"做成显眼告警，而不是只在诊断里体现。

### P0-2 敏感通知权限曾被拒且界面误报

**现象**：`appops` 里 `rejectTime` 显示两天前被拒，11:17 才变 allow。
旧代码用 `"android:receive_sensitive_notification"` 查 op，与本机实际名字对不上，
检测永远失败，界面一直显示「需开启」——即使权限是开的。

**影响**：用户无法判断真实状态；QQ/微信这类聊天通知可能读不到内容。

**已修（2.7.8）**：改为三层兜底（官方函数 → 多个字符串名 → `getOpByName` 数字编号），
界面现在正确显示「✔ 已允许，可读取聊天类通知内容」。

**建议**：权限为 `-1`（被禁止）时给出明确文案并直达设置页；不要只依赖检测为 0 的兜底。

### P1-1 模块的拉起广播依赖 app ≥ 2.7.6

`WakeReceiver` 是 2.7.6 新增的。旧版本收到广播无人处理，模块会记
「拉起失败：进程未出现（检查应用是否为 2.7.6+）」。

**建议**：模块版本说明里标注依赖；或模块先探测组件是否存在再决定是否发广播。

### P1-2 保护标记 mtime 不刷新会导致模块误判"陈旧"

`files/active` 只在 `KeepAliveService` 创建/`onStartCommand` 时刷新 mtime。
如果进程长期活着但服务没重启，标记会越来越旧。10:45 就出现过
「连续 3 次复查失败 → 判定保护标记陈旧 → 暂停高频解冻」。

**建议**：`NotificationReaderService` 的心跳里顺带刷新一次 mtime，成本极低。

### P1-3 模块只检查"进程在不在"，不检查"监听/服务是否健康"

`wake_app()` 以 `app_pid` 为空作为唯一触发条件。进程活着但监听没绑、服务没起时，
模块什么都不做（本次就是这样静默失效了 80 多分钟）。

**建议**：把 P0-1 的绑定检查并入模块的周期性自检（比如每 5 分钟一次）。

### P1-4 TTS 引擎是单点依赖

播报依赖系统 TTS（当前 `com.oplus.ttsaccessibilityengine`）。
引擎被禁用、更新失败或被系统省电策略限制时，播报会静默失败。

**建议**：`onInit` 失败时在界面给出明确提示（目前主要靠日志）。

### P1-5 白名单需要手动维护

当前 11 个应用，新增应用必须进"播报白名单"才会念。用户容易误以为是故障。

**建议**：首次配置时给出更明确的说明；或在诊断报告里列出白名单内容。

### P2-1 `checkSensitiveByNotificationFunction` 的参数名可疑

反射调用 `canUseNotificationFunction("android.access_internally_sent_notifications")`，
本机返回不可用（走不到）。目前靠 op 兜底，可考虑删掉这段或换成正确的函数名。

### P2-2 ColorOS 的 `OplusHansManager` 会拦截服务启动

`am start-service/-foreground-service` 一律被代理后返回
「Not found; no service started」。已改用显式广播绕过，但说明这台机器对
"后台启动服务"的管控很严，后续新增拉起方式要先实测。

### P2-3 app 与模块存在版本耦合

广播拉起需要 app ≥ 2.7.6 + 模块 ≥ v1.6。两者分开安装，升级时容易只升一边。

**建议**：发版说明里固定写清"app 与模块的配套版本"。

### P2-4 `interruption_log` 可能误报

中断记录靠心跳断档反推，设备长时间深度休眠也会被记成"中断"。当前记录里有
`09:56 → 11:17 中断约 81 分钟`（真实，是重装导致）和更早的几条，需要结合日志判断。

## 三、本次改动

- `MainActivity.sensitiveNotificationState()`：三层检测兜底，修复 op 名不匹配导致的误报
- 检测不到时文案改为「系统未暴露检测接口，若聊天通知读不到请点此开启」，不再断言未开启
- 版本 2.7.8 / versionCode 25
