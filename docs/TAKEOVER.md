# 接手说明（2026-11-06 当前状态）

## 一句话

Android 应用「耳机通知播报」（`com.sakura.airannounce`）：蓝牙耳机连接时朗读通知。
app 侧负责读通知 + TTS，root 模块侧负责防止 ColorOS 冻结进程。

## 当前版本

| 组件 | 版本 | 说明 |
|---|---|---|
| app | 2.7.8 / versionCode 25 | 已在设备安装 |
| root 模块 | v1.6 / versionCode 7 | `/data/adb/modules/airannounce-thaw` |
| 分支 | `main` | 最后提交见 `git log` |

## 设备当前状态（2026-11-06 11:30 实测）

- 通知监听：已绑定（`Live notification listeners` 含本应用）
- 敏感通知权限：`RECEIVE_SENSITIVE_NOTIFICATIONS` = allow
- 前台服务：`isForeground=true types=0x10`，`adj=200`
- 播报开关：开；白名单 11 个应用（含 `com.tencent.mobileqq`）
- 电池优化：已豁免
- 模块：high 模式，5 秒一档，解冻计数正常
- 实测 QQ 消息可正常播报

## 今天做过的事（按时间）

1. 冻结复现实验：确认 cgroup v2 路径 `/sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze`，
   手动注入冻结并用模块解冻，实测解冻延迟 3.9–4.05 秒（详见 `docs/FREEZE-TEST.md`）
2. 发现模块 `wake_app` 用 `am start-foreground-service` 启动 `KeepAliveService` 会失败
   （服务 `exported="false"`，被 ColorOS `OplusHansManager` 拦截，返回 "Not found"），
   且旧判定只看 PID 非空 → 日志假阳性
3. 改成 `am start-activity`（v1.5），实测会把屏幕从 Dozing 打回 Awake，体验差
4. 改为显式广播（v1.6）：app 新增 `WakeReceiver`，模块 `am broadcast` 拉起，
   实测不弹界面、不亮屏
5. 修复敏感通知权限检测（2.7.8）：旧代码 op 名与系统不匹配，界面一直误报「需开启」
6. 记录自检与隐患清单：`docs/SELF-CHECK-2026-11-06.md`

## 必读的坑

### 1. 重装 APK 后通知监听不会自动重绑（最高优先）

三次安装（2.7.6 / 2.7.7 / 2.7.8）后都复现：授权字符串正常，但
`NotificationReaderService` 显示 `app=null`，`Live notification listeners` 里没有本应用，
**所有播报静默失效**。

每次都必须手动执行：

```sh
C=com.sakura.airannounce/com.sakura.airannounce.NotificationReaderService
cmd notification disallow_listener $C 0
cmd notification allow_listener $C 0
```

安装后务必检查：

```sh
dumpsys notification | sed -n '/Live notification listeners/,/Snoozed/p' | grep -c sakura
# 输出应为 1
```

### 2. ColorOS 的 OplusHansManager 会拦截服务启动

`am start-service` / `am start-foreground-service` 一律返回
`Error: Not found; no service started.`，logcat 里能看到
`OplusHansManager: proxyService ... [xxx/null] calling 0/com.android.shell`。
不要再用这两个命令拉起本应用，用广播（`WakeReceiver`）。

### 3. 模块必须跑在 init 挂载命名空间

手动启动要这样，否则看不到 `/data/data`，`$FLAG` 永远不存在、静默失效：

```sh
nsenter -t 1 -m sh /data/adb/modules/airannounce-thaw/service.sh
```

开机由 KernelSU 执行，不受影响。

### 4. 拉起的副作用

`am start-activity` 会点亮屏幕，不要用。
广播拉起不亮屏，实测 `mWakefulness` 保持 `Dozing`。

## 常用命令

```sh
# 构建（Linux 环境，QEMU 跑 aapt2）
bash /workspace/build.sh
# 产物：/storage/emulated/0/Download/QQ/airannounce-debug.apk

# 安装（/storage 有 SELinux 限制，必须先拷到 /data/local/tmp）
cp /storage/emulated/0/Download/QQ/airannounce-debug.apk /data/local/tmp/aa.apk
chmod 644 /data/local/tmp/aa.apk
pm install -r /data/local/tmp/aa.apk

# 手动拉起（不弹界面）
am broadcast --user 0 --include-stopped-packages \
    -a com.sakura.airannounce.WAKE -n com.sakura.airannounce/.WakeReceiver

# 模块日志
tail -f /data/adb/modules/airannounce-thaw/keepalive.log

# 冻结点 / 解冻
cat /sys/fs/cgroup/apps/uid_10645/pid_<pid>/cgroup.freeze   # 0 运行，1 冻结
```

## 待办（按优先级，详见 `docs/SELF-CHECK-2026-11-06.md`）

### P0-1 监听绑定自愈（建议实现方案）

不要用「每 5 分钟 dumpsys」这种重轮询。推荐：

- app 侧：`onListenerConnected()` 写 `files/listener_connected`，
  `onListenerDisconnected()` 删除它（真正的事件源）
- 模块侧：只在 **PID 变化**时，以及 high 模式每 12 tick 时，`stat` 这个文件；
  若进程启动超过 30 秒仍没有该文件，执行一次 `cmd notification allow_listener`
- 成本从「几十 KB 的 dumpsys」降到「一次 stat」

注意：shell 脚本没有 binder 客户端，做不到真正的事件回调；要真事件驱动只能常驻一个
`app_process` 的 Java 进程，复杂度不划算。

### P1-2 不要在心跳里刷新 `files/active` 的 mtime

`mtime` 现在的语义是「应用主动声明需要保护」，并且只在退出 `STALE_BLOCK` 时使用：

```sh
if [ "$STALE_BLOCK" = "1" ]; then
    CUR_MTIME=$(stat -c %Y "$FLAG")
    [ "$CUR_MTIME" != "$FLAG_MTIME" ] && STALE_BLOCK=0   # 恢复高频
fi
```

如果心跳刷新 mtime，`STALE_BLOCK` 会在一个心跳周期内被自动解除，
**降频自愈机制直接失效**（前台服务已经没了，模块还在 5 秒空转）。
另外还会让 mtime 从「服务活着的信号」变成「线程活着的信号」，掩盖监听/服务的真实故障。

结论：mtime 保持不动，健康状态另开文件。

### 其他

- P1-1 模块拉起依赖 app ≥ 2.7.6（`WakeReceiver`）
- P1-3 模块只检查进程存在，不检查监听健康
- P1-4 TTS 引擎单点依赖（当前 `com.oplus.ttsaccessibilityengine`）
- P1-5 白名单需手动维护
- P2 见自检文档

## 文件地图

```
app/src/main/java/com/sakura/airannounce/
  KeepAliveService.java       前台服务，写 files/active 标记
  NotificationReaderService.java  通知监听 + TTS + 心跳
  WakeReceiver.java           给 root 模块用的显式广播入口
  MainActivity.java           设置界面 + 诊断

keepalive/service.sh          root 守护脚本（解冻 + 低频探测 + 广播拉起）
keepalive/module.prop         模块版本信息

docs/
  SELF-CHECK-2026-11-06.md    隐患清单（P0/P1/P2）
  FREEZE-TEST.md              冻结注入实验与解冻延迟实测
  MEASUREMENT-INTERVAL.md     采样间隔四组对照
  POWER-REVIEW*.md            功耗评审与回应
  TAKEOVER.md                 本文件
```

## 轮询调度改造（2.7.9 / versionCode 26）

### 改了什么

轮询循环不再无条件常驻，改为跟随「开关 + 耳机」两个条件启停：

- `startPolling(delayMs)`：耳机在、播报开着才把 `pollRunnable` 挂上；已在跑时直接返回，
  不会被 A2DP / HSP 连续两条设备广播反复重置倒计时。
- `stopPolling()`：耳机一断就 `removeCallbacks(pollRunnable)`，只留 `idleHeartbeatRunnable`。
  同时 `resetPollingState()` 复位指纹表与 `pollInitialized`，避免重连后把断开期间的
  旧通知补念一遍。
- `idleHeartbeatRunnable`（`IDLE_HEARTBEAT_MS = 120_000`）：空闲期每 2 分钟醒一次，
  作用是 ① 写心跳（实际被 `HEARTBEAT_WRITE_INTERVAL_MS = 180_000` 节流到 3 分钟，
  远小于 `HEARTBEAT_GAP_THRESHOLD_MS = 420_000` 的断档判定）② 当看门狗：
  `checkBluetoothFresh()` 强制重查真实音频输出，ROM 没发设备回调时也能在 2 分钟内
  发现耳机其实连着，把轮询救回来。
- 状态页诊断多一行 `· 轮询：已暂停（未连接耳机），上次 tick N 秒前`，用来证明轮询
  是真的停了，而不是"看着没播报"。

调用点：`onCreate` / `onListenerConnected` / `AudioDeviceCallback` 增删 /
`MainActivity.syncKeepAlive()`（经 `syncPollingIfAvailable()`）。

### 为什么这么改

5 秒 tick 在无耳机时纯属空转：它持续唤醒进程，App 进不了深度空闲。
而"没有耳机"是常态（一天里占绝大多数时间），这部分唤醒没有任何收益。

**注意：本次只做了一半。** 系统闹钟 / JobScheduler 替代 tick 的部分没做，原因见
`docs/POLLING-PLAN.md`：`cgroup.freeze` 只有 root 模块或系统投递广播能解冻，
闹钟在冻结期间投递不可期，替代不了模块；且粒度会退化到 9–15 分钟。
真正能考察的收益点在这个"耳机在才转"的开关上，先量耗电再决定走不走第二步。

### 验证记录（2026-11-08）

- 装机后无耳机：无 `Polling started` 日志 → 轮询确实没起
- 心跳文件 mtime 11:59 → 12:03 自行刷新 → 空闲心跳在跑
- 状态页显示"已暂停"，`lastPollTickMs` 为 0
- **未验证**：接上耳机后 `Polling started` 与 `轮询：运行中` —— 当时无可用耳机

## 监听绑定自愈（2.7.12 / versionCode 29）

### 现场（2026-11-10 20:27）

```
pid=25372            进程在
isForeground=true    前台服务在
耳机端口=2           耳机连着
cgroup.freeze=0      没被冻
监听绑定=0           ← 问题在这里
settings.enabled_notification_listeners 里仍有本应用  ← 授权没丢
```

即：**授权还在、进程活着、耳机连着，但 NotificationListenerService 没有绑定**，
而模块只看 `pidof`，看不见这一态；`requestRebind` 发过一次就不再管，
失败也没有任何回调 —— 于是静默不播报，直到有人手动开一下 App。

### 修法（App 侧，不需要 root）

1. `requestRebindIfNeeded()` 改成带复核的重试链：3s / 10s / 30s 各复核一次，
   `instance != null` 即停；链只允许存在一条（`rebindChainRunning`），
   否则 WakeReceiver、KeepAliveService.onCreate、蓝牙广播会在同一秒各起一条链，
   日志里同一个 attempt 会重复三次。
2. `KeepAliveService` 加 60 秒自检：`NotificationListenerService` 被解绑时它自己已经
   不在了，没有"外部视角"能发现；保活服务在前台、耳机连着时一直活着，正好当这个视角。
   发现 `getInstance() == null` 就重绑。

### 实测

- 装包后监听必然掉（`pm install -r` 会解绑）→ 广播唤醒 → **9 秒内自动绑回**，
  全程无需 `cmd notification allow_listener`
- 单链验证：`attempt 1 → 3s → attempt 2 → onListenerConnected`，无重复
- 60 秒自检验证：`disallow_listener` 后第 37 秒该检查触发，按 3/10/30 秒重试；
  权限被真正撤掉时只能等人重新授权，这一点代码里用 `recordEvent` 留了痕

### 仍未覆盖

- **不带耳机时**保活服务是停的，60 秒自检也随之停。此时监听掉线要靠模块广播拉起
  （`WakeReceiver`）才会重试；无 root 设备没有这一环。
- 模块侧仍只检查 `pidof`，没有 `dumpsys notification` 复核（P0-1 的模块半边没做）。
