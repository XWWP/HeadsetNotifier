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

## 监听自愈：模块侧（模块 v1.7 / versionCode 8）

### 为什么 app 侧不够

`NotificationListenerService.requestRebind()` 在这台 ROM 上经常是**空操作**：
app 确实发了请求，系统不理，日志里只会反复出现
`listener still unbound after 3000ms, retrying.`，退避到第 4 次后放弃。

2026-11-11 复现 3 次，全部失败；而手工 `disallow_listener` → `allow_listener`
切一次，3/3 全部成功。所以真正有效的手段是"切换"，不是"请求重绑"。

### 模块怎么做的

`keepalive/service.sh` 的 high 模式里，每 12 个 tick（约 60 秒）：

```sh
if listener_granted && ! listener_bound; then fix_listener; fi
```

- `listener_granted`：`settings get secure enabled_notification_listeners` 里有本应用
- `listener_bound`：`dumpsys notification` 的 `Live notification listeners` 段里有本应用
- `fix_listener`：`disallow_listener` → sleep 1 → `allow_listener` → sleep 2 → 复核
- 连续失败 3 次就停手（`LISTENER_FAIL`），避免对着无解的状态死循环

**安全边界**：只有"权限本来就在、只是没绑上"才动手。用户主动撤权时
`listener_granted` 为假，模块绝不替他改回去。

### 实测（强杀 → 自动恢复）

```
11:09:09  am force-stop com.sakura.airannounce      （我自己触发）
11:09:14  应用进程已不在，退回低频待机
11:09:58  拉起成功 pid=20387
11:10:18  应用要求保护，进入高频解冻
11:11:14  监听未绑定（权限仍在），执行 disallow/allow 切换
11:11:17  监听已重新绑定
```

从强杀到恢复约 **2 分 8 秒**，全程无人干预。

### 仍存在的边界

- 没有 root、也没装 Shizuku 的设备，没有这一层。app 侧只能靠
  `requestRebind`（不可靠）+ 保活服务的 60 秒自检，恢复率不保证。
- 恢复时间取决于 tick 相位：最坏要等一个 60 秒检查周期。

## Shizuku 通道补齐（2.8.0 / versionCode 30）

模块 v1.7 那套 `disallow → allow` 切换，现在 app 侧也有一份，给无 root 设备用：

- `ShizukuChannel.healListenerAsync()`：先确认「通知使用权在」+「Shizuku READY」，
  然后在后台线程执行 `cmd notification disallow_listener … 0; sleep 1; cmd notification allow_listener … 0`
- 连续失败 3 次冷却 10 分钟（`healFailStreak` / `healBlockedUntilMs`）
- `KeepAliveService` 的 60 秒自检优先调它，返回 false（没装/没跑/没授权）才退回
  `requestRebind`

### 已验证 / 未验证

- 已验证：Shizuku 通道本身可用。启动 Shizuku（root 方式）→ `pm grant … API_V23`
  → 重启 app 进程后 `shizuku_last_applied_at` 从 1794408010885 变成 1794408076774，
  说明 `run()` 里的三条命令在 2.8.0 上确实执行成功（只有全 ✓ 才写这个时间戳）。
- 未验证：**heal 的触发**。需要一个「权限在、监听未绑」的状态才能触发，
  试过「冻结进程 + disallow/allow」制造这一态，系统在解冻后自己绑回去了，造不出来。
  切换命令本身在模块侧已实测 3/3 成功，heal 只是换了执行者。

### 无 root 时的实际保活边界

| 能力 | 有 Shizuku |
|---|---|
| 监听掉线自修 | 能（前提：app 进程活着、Shizuku 在跑） |
| 解冻 cgroup | 不能（shell uid 写不了） |
| 进程被杀后自动拉起 | 不能（Shizuku 不会替你定时） |
| 重启后 | 非 root 需要人重新激活 Shizuku |

## 事故记录：重启后 23 分钟哑火（2026-11-12）

### 现象

诊断报告：`✗ 监听服务：未运行`，最近处理记录里每 40 秒一轮
「自检：监听未绑定，尝试重绑」+「监听重绑多次未生效」，持续 23 分钟没恢复。
中断日志显示当天多次断档：8 分钟、12 分钟、15 分钟、28 分钟、53 分钟、
142 分钟、287 分钟。

### 三个原因叠在一起

1. **设备重启过**（uptime 58 分钟）。重启后 Shizuku 服务没在跑 —— 非 root 的
   Shizuku 本来就要重新激活，所以 app 侧 `healListenerAsync()` 直接返回 false，
   只剩 `requestRebind`，而它在这台 ROM 上是空操作。
2. **模块试过，但 3 次后彻底停手**。日志：
   `18:38:14 监听未绑定（权限仍在），执行 disallow/allow 切换`
   `18:38:17 监听重绑失败（3/3）`
   `LISTENER_FAIL` 达到 3 之后没有任何归零逻辑，等于永久放弃修复 —— 这是我上一版
   引入的缺陷。手动执行同一组命令 0 → 1 立即成功，说明命令本身没问题，
   是"2 秒后没绑上就判失败"的判定太急（进程刚解冻/刚重启时绑定要几秒）。
3. **失败原因被吞掉**：日志只有"失败"，没有当时到底有几个 listener。

### 修正（模块 v1.8 / versionCode 9）

- 切换后先等 3 秒；仍未绑上就 `wake_app` 叫醒进程，再等 3 秒复核
- 连续 3 次失败改为冷却 120 tick（10 分钟）后重试，不再永久停手
- 保留 `LISTENER_FAIL` 归零逻辑（绑上即清零）

### 仍然存在的结构性问题

重启后：Shizuku 死、`requestRebind` 无效、模块是唯一可用手段。
没有 root 的设备在这一刻没有任何自动修复能力，只能等人手动去通知使用权里
关一次再开一次。这属于"非 root 保活"的硬边界，不是 bug。
