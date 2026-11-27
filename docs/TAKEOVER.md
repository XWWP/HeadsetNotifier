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

### P0-1 监听绑定自愈（建议方案，已作废）

> **已作废（v1.7+）**：实际落地的是 `dumpsys notification` 段内匹配 + `cmd notification
> disallow_listener/allow_listener` 切换（`keepalive/service.sh:139-173`），app 侧走
> `ShizukuChannel.healListenerAsync`。下面这套 `files/listener_connected` 信标从未实现，
> 保留仅为记录当时的取舍：

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

## 事故记录：循环依赖导致“永久哑火”（2026-11-19）

### 现场

```
蓝牙已连接（2 个音频端口）
进程 25284 活着
监听绑定 = 0
前台服务 = 0
模块日志：心跳 模式=low … 待机中检测到蓝牙音频输出，已尝试拉起（无保护标记：应用未要求保护）
```

持续约 20 分钟没有任何自动恢复。

### 这是三条腿组成的死锁

1. 监听没绑 → `NotificationReaderService` 不会被创建
2. 它的 `onCreate` 里才会 `ensureKeepAliveIfNeeded()` → 前台服务没起来 →
   `files/active` 不生成
3. 模块看不见标记 → 停在 low；而**监听修复只写在 high 分支**里（v1.7/v1.8 都这样）
4. 同时 `wake_app()` 有 `[ -n "$(app_pid)" ] && return 0`：进程在就不再广播，
   而广播正是唯一能让 `syncKeepAlive` 重跑、把前台服务拉起来的入口

结果：没有任何一方会去打破它，只能等人手动切换监听。

### 修正（模块 v1.10 / versionCode 11）

- **low 模式也查监听**：每 3 个 tick（约 60 秒），条件同为
  「播报开着 + 蓝牙音频在 + 权限在 + 未绑上」，与 high 共用同一套计数与 10 分钟冷却
- **wake_app 不再只看进程**：进程在但 `files/active` 缺失时也广播，
  间隔从 60 秒放宽到 5 分钟（这种状态本就异常，不该高频打扰）

### 实测

强杀 + 删标记后：`17:53:23` 强杀 → `17:53:27` 拉起 → `17:53:47` 进入高频 →
约 85 秒后监听恢复绑定、前台服务恢复。low 分支的监听修复这次没被触发到
（进程被拉起后前台服务自己起来了），属于代码级修正。

## 监听掉线提醒（2.8.4 / versionCode 34）

### 为什么提醒要放在 WakeReceiver 里

初版把提醒写在 `KeepAliveService` 的 60 秒自检里。2026-11-19 的死锁恰好证明了这不够：
那一次前台服务根本没起来，自检代码一行都没跑，提醒自然不会响。

现在两个发出点：

| 发出点 | 何时生效 |
|---|---|
| `KeepAliveService` 60 秒自检 | 前台服务在跑时 |
| `WakeReceiver`（模块每次广播都会到） | 前台服务起不来时唯一还能到达的入口 |

`WakeReceiver` 里同时做三件事：`syncKeepAlive`、优先用 Shizuku 修监听
（没 Shizuku 才退回 `requestRebind`）、以及判是否需要提醒。

### 提醒规则

- 连续未绑 **10 分钟** → 发通知（点一下直达「通知使用权」设置页）
- 同一问题 **1 小时内只提醒一次**（初版设 6 小时，实测太钝：修好后又坏的情况会被吞掉）
- 绑定成功即撤销通知并清空计时（`onListenerConnected` 里做，避免旧通知挂在通知栏）

### 实测

用「强杀 + 删标记 + 把 `listener_down_since_ms` 改成 11 分钟前」制造状态：

```
提醒通知 = 1（监听仍是 0）
→ 模块 60 秒内修好 → 监听 = 1 → 提醒通知 = 0
```

撤销逻辑和提醒逻辑都验证到了。

## 重启验证（2026-11-19，模块 v1.11）

### 时间线（v1.10 时的表现）

```
18:11:20  模块启动（KSU 自动）
18:12:04  系统就绪，开始守护
18:15:50  拉起成功 pid=4452        ← 中间 3 分 46 秒在等用户解锁（/data 不可读）
18:16:10  进入高频解冻
18:17:05  监听未绑定（权限仍在），执行 disallow/allow 切换
18:17:10  监听已重新绑定
```

结论：**重启后能自愈，但从开机到恢复约 6 分钟**，其中大头是"等解锁"，
小头是"首次监听检查要等满 12 个 tick（60 秒）"。

### v1.11 的调整

- high 模式：`TICK_HIGH == 1` 也检查（原来是 `% 12 == 0`，第一次要等 60 秒）
- low 模式：第一个 tick 就检查（原来要等第 3 个 tick）

最多省下约 55 秒。剩下的"等解锁"是 FBE 的硬限制，代码层面无解。

### 复测（升级到 2.8.5 后装包触发的解绑）

`pm install -r` 会让监听解绑，装完约 70 秒内恢复绑定，无需人工干预。

---

## 应用 → 守护模块的反向请求通道（2.9.4 / 模块 v1.16）

### 起因：应用自己 su 在 KernelSU 上是死路

这次试着给应用补一条 root 自愈腿（`RootChannel`，用 su 直接拿 uid 0），实测发现根本走不通：

```sh
# 以 app uid 执行
$ /system/bin/su 10645 -c "/system/bin/su -c 'id -u'"
/system/bin/sh: /system/bin/su: inaccessible or not found
```

应用侧对应的日志是：

```
I AirAnnounce: root probe failed: IOException
I AirAnnounce: root available=false
```

判据说明：`su 10645 -c "id -u"` 能返回 10645（root 自己调用 OK），app uid 却连二进制都看不到：

```sh
$ su 10645 -c "ls -la /system/bin/su"
ls: /system/bin/su: No such file or directory
$ su 10645 -c "stat -c '%a %U %Z' /system/bin/su"
stat: '/system/bin/su': No such file or directory
```

注意这里是 **ENOENT**，不是 EACCES：`inaccessible or not found` 是 shell 找不到命令的措辞，
SELinux 拒绝会是 `Permission denied`。根因在 KernelSU 的授权模型 —— 它只对**已授予 root 的
UID** 做 su 的 exec 重定向，未授权的 app uid 在文件系统层面就看不到该文件，fork/exec 直接失败。
所以这不是「没授权」，而是「没授权时连 su 二进制都不可见」；换绝对路径、换 PATH 都不解决。

同时 KernelSU 的 CLI 没有任何「加授权」子命令（`ksud --help` 只有 module / profile /
sepolicy 等），授权只能通过管理器 UI 点，而 UI 弹窗只有在 exec 成功的前提下才会出现。

结论：**「有 root 就让应用自己提权」这个设计在 KernelSU 上不成立。**

### 方案：应用投递请求，守护代执行

应用侧写自己的私有目录不需要任何权限，这条路天然可用。

| 方向 | 文件 | 内容 | 谁写 | 谁读 |
|---|---|---|---|---|
| 应用 → 模块 | `files/heal_request` | 毫秒时间戳 | 应用 | 守护（root） |
| 模块 → 应用 | `files/module_seen` | 秒级时间戳 | 守护（root） | 应用 |

要点：

- `heal_request` 用「写临时文件 → fsync → rename」落盘，守护随时可能来读，
  读到半个时间戳会被当成新请求反复触发。
- 守护只认**内容变化**（`LAST_HEAL_REQ`），应用保留文件不删，避免写删竞争。
- 守护处理前会 `listener_granted && ! listener_bound_now` 复查一遍，
  监听其实正常就只记一行日志、不瞎切。
- `module_seen` 由守护每分钟刷 mtime，写完必须 `chown` 回应用 uid ——
  root 属主的 600 文件 untrusted_app 读不到，心跳就白写了。
- 应用判存活只看 `module_seen` 的 mtime（3 分钟内算活），不读 `/data/adb`
  （untrusted_app 读不到）。

### 自愈优先级（`ListenerHeal`）

1. `ModuleChannel`（守护模块）—— 有 root 时唯一可靠通道
2. `RootChannel`（su）—— Magisk / 已授权 KernelSU 上仍可用，没装模块时的补充
3. `ShizukuChannel` —— 没有 root 的设备上的降级方案
4. `NotificationReaderService.requestRebindIfNeeded` —— 官方 API，本 ROM 上常是空操作

### 实测

```
20:11:38 守护启动 pid=28873 版本=v1.16
20:11:54 收到应用监听修复请求（1795781513585）
20:11:54 复查后监听仍正常，忽略该请求
```

UI 同步改成三态，不再只认 su 和 Shizuku：

```
提权通道：守护模块（root）｜状态：已安装，但 Shizuku 未运行（重启后需重新激活）
```

本机实测：`pm install -r` 装包触发的解绑，10 秒内自动 rebind 恢复（走的是应用自身
重绑路径，还没轮到模块出手）。

### 已知限制

- `RootChannel` 在本机永远返回 false（ENOENT：KernelSU 只对已授权 UID 重定向 su），
  保留它是为了 Magisk 设备和「已授权且能 exec su」的 KernelSU 变种，
  不要因为本机不走它就删掉。探测失败按 5 分钟缓存，不会每次自检都抛一次 IOException。
- `fix_listener` 会阻塞守护循环约 7 秒（disallow → sleep 1 → allow → 复查），
  期间解冻节拍顺延，不影响功能。

---

## 第二轮：闭合性修复（应用 2.9.x / 模块 v1.17）

第一轮改完之后 Claude 的复审结论是「方向对，但两处都缺闭合性——状态没串行化，异常出口没收口」。
这一轮就是补闭合：把「异常出口」逐个补上，并给每一条第一次拿到实测证据。

### 1 自愈窗口回滚（最危险的一条）

**问题**：切换绑定是 `disallow → sleep 1 → allow`，中间有一段授权为空的窗口。
执行者（守护 / su / Shizuku / 发起请求的应用进程）在这个窗口里被杀、被冻、被重启，
权限就停在空的状态；而之后每一条自愈路径的前置条件都是「权限本来就在」，
永远不成立 —— 监听永久关闭，只能人工去系统设置里重新授权。

**做法**：动手前落盘一个「切换中」标记，补回或确认已绑定后再删。两侧各用各的文件，刻意不共用：

| 执行方 | 标记文件 | 理由 |
|---|---|---|
| 应用自己的 Root / Shizuku 通道 | `files/heal_inflight`（应用私有目录） | 应用只能写自己的目录 |
| root 守护 | `$MODDIR/heal_inflight`（模块目录） | 守护往应用目录写要处理属主与 SELinux 标签；且 FBE 解锁前应用目录对守护不可见，而开机早期崩溃正是要防的场景 |

守护每个节拍（5 秒）检查自己的标记：

- 年龄 < 5 秒 → 不动手（`fix_listener` 可能正跑在同一拍的后台子进程里，抢跑会破坏它自己的 sleep）
- `listener_granted=false` → **立即补 `allow_listener`**（这就是要救的状态，不等 20 秒）
- `granted=true` 但未绑定且年龄 ≥ 20 秒 → 重新切一次
- 年龄 > 10 分钟 → 删标记放弃（多半是用户自己撤了授权，不能替他改回去）

`fix_listener` 自身补了两件事：前置写标记；**成功判定从「信退出码」改成 `listener_bound_now && listener_granted`**
（`cmd` 在多数失败情形下也返回 0，只有 `enabled_notification_listeners` 里真的还有本组件才算授权回来了）。
另外加 30 秒节流 —— 本机实测没有节流时会连切 3 次，越切越乱。

**实测**（首次跑通这条路径，此前只有「复查后忽略」分支的证据）：

```
20:34:39 上次切换把授权撤掉了（12 秒），立即补 allow_listener
20:34:41 监听已补回
```
撤销前授权数 1 → 手工 `disallow` 后 0 → 守护补回后 1，`heal_inflight` 已删除。

真实 `fix_listener` 执行分支的证据（装包触发的解绑）：

```
20:33:57 监听未绑定（权限仍在），执行 disallow/allow 切换
20:34:01 监听已重新绑定
20:42:16 距上次切换不足 30 秒（20 秒），本轮跳过
```

### 2 播报音量落盘保险

**问题**：boost 之后原音量只在内存里，进程被冻进 freezer 时 app 内定时器停摆，
音量停在抬高值上一直冲耳朵。

**做法**：新增 `SpeechVolumeGuard`，文件 `files/volume_original`，内容是

```
original  target  max  leaseUntilWallMs  deviceKind
```

三个关键设计，都是为了避免「保险把自己弄成事故」：

1. **租约**。文件在整个会话期间都存在；只看「文件在」就恢复，会把念到一半的音量压回去。
   每次开口都续租 `leaseUntil`，别人只在租约过期后才动手。
2. **设备**。音量按输出设备分别存。耳机已拔却把原值写进去，写的是扬声器那一档，
   耳机那一档还留在 boost 值上 —— 下次连上直接炸耳朵。记录了 `bt/other`，设备对不上就先不动。
3. **比对**。恢复前比对 `当前 ≈ target`（±1 档容差，A2DP 绝对音量有取整）。
   用户自己拧过音量就不覆盖他的选择，直接弃文件。

配套：还原延迟 400ms（A2DP 管线 200~300ms，`onDone` 往往早于声音真正放完，立刻还原会削掉尾音），
且延迟任务执行时若已有新会话在念就放手 —— 否则它会把新会话刚提上去的音量和刚写的记录一起抹掉。

**这一轮修掉的真 bug**：`restoreVolumeAfterSpeech()` 原来无条件 `SpeechVolumeGuard.clear()`，
即使本轮根本没提过音量。实测直接把「另一个会话正在用的租约」删掉了（写一个 lease 未到期的标记，
12 秒后文件消失）。现在只有 `savedMusicVolume >= 0`（本进程确实提过）才允许删。

**守护侧不再用命令行改音量**。本机实测 `cmd media_session volume --stream 3 --set N` 会 clamp：

```
set 26  → 16
set 60  → 50
set 140 → 100
```

拿它「还原」等于随机改用户音量，戴耳机时就是往耳朵里怼（测试过程中确实把音量推到过 100，
当场炸了使用者的耳朵，这个操作方式已废弃）。守护现在只做一件事：标记过期就 `wake_app`
（30 秒防重入），让应用自己用 `AudioManager` 精确还原。

**实测**：
- 租约未到期写入标记，12 秒后文件仍在（不动手）✔
- 租约过期后，应用侧 20 秒内清理掉文件 ✔
- 待验证：真实 boost → 延迟 400ms 还原（需要在没有戴耳机的时候做，避免再出声）

### 3 su 归因修正

`inaccessible or not found` 是 shell 找不到命令（**ENOENT**），SELinux 拒绝会是 `Permission denied`。
根因是 KernelSU 只对**已授予 root 的 UID** 做 su 的 exec 重定向，未授权的 app uid 在文件系统层面看不到该文件：

```sh
$ su 10645 -c "ls -la /system/bin/su"
ls: /system/bin/su: No such file or directory
$ su 10645 -c "stat -c '%a %U %Z' /system/bin/su"
stat: '/system/bin/su': No such file or directory
```

改掉的位置：`docs` 本节上文、`ListenerHeal` 类注释、`ModuleChannel` 类注释、`MainActivity` 的两处说明文案、
`service.sh` 主循环里那条注释。`RootChannel` 保留（Magisk 与已授权 KernelSU 变种仍需要），
探测失败按 5 分钟缓存，不再每次自检都抛一次 IOException。

### 4 队列年龄顺延设上限 + 过期条目合并

原来 `deferQueueAge()` 把整队 `queuedAt` 直接推到当下，**无上限**：一个 40 分钟的电话结束后，
会把 40 分钟前的通知一条条念出来。现在：

- 每条记录 `enqueuedAt`（原始进队时刻，永不改）作为基线
- 顺延上限 `QUEUE_AGE_DEFER_MAX_MS = 60s`，即 `queuedAt = min(now, enqueuedAt + 60s)`
- 顺延后仍然过期的条目**合并成一条摘要**「已跳过 n 条未播报的通知」入队，而不是静默丢弃；
  摘要自身不参与再合并，新摘要替换旧摘要并累加计数（避免「摘要的摘要」）

### 5 CAN_DUCK 不再主动压音量

原来收到 `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` 就把 `STREAM_MUSIC` 压一半。问题是 boost/restore 动的是
**同一条流**，导航提示音、用户自己的音乐都在上面，压一半等于把它们一起压掉；
而通知是叠在导航上面的语音，压自己并不会让自己「更清楚」。

现在只保持继续播、不碰音量（`CONTENT_TYPE_SPEECH` 的 duck 交给系统），同时删掉了 GAIN 分支里的「抬回」——
不删的话它会把音量抬到一个错误的值。

### 6 暂停态禁止投递 + 延迟还原

- 新增 `PAUSED` 态：`drainAnnouncementQueue()` 在非 `IDLE` 时直接返回，暂停期间不会念下一条
- 暂停时不还原音量（会话还要继续，还原会掉一下声音）
- 出口都在：`GAIN` → `resumeFromPause()`；永久 `LOSS` → 清队并收尾；焦点申请被拒 → 3 秒后重试；
  暂停 5 秒后兜底 `resumeFromPause()`
- `stopEverything` / 拔耳机 / 服务销毁 / 永久丢焦点 → `endSpeechSessionNow()`（立即还原，不延迟 400ms）

### 7 收成显式状态机

11 条补丁叠在 6 个独立布尔上，任何一处出口忘了置位就是「焦点和抬高后的音量一直挂着」。现在：

| 项 | 做法 |
|---|---|
| 态 | `SpeechPhase{IDLE, SPEAKING, PAUSED}`，所有出口走 `finishSession()` |
| 回调归属 | `activeToken` 单一 token；对不上的回调一律丢弃（冻结期 oneway 回调解冻后集中爆发） |
| token 编码 | `s<会话>-<条目>-r<重试>-p<重播>`，重试与重播分开计数，避免和上一次失败的幽灵回调撞 id |
| 守卫 | 拆两段：启动守卫 5s（引擎不开口）+ 完成守卫按字数（封顶 60s） |
| 假超时 | 守卫实际触发比计划晚 2 秒以上 → 说明线程被冻过，宽限一次后重新武装 |
| 重试 | `MAX_SPEAK_RETRIES = 2`；连续失败 3 次重建引擎 |
| 重建 | 退避 3s×n（封顶 30s），每小时最多 6 次，超限后只记录不再重建 |
| 不变量 | `finishSession()` 末尾检查 `activeToken / currentItem / audioFocusRequest` 是否清空，不清就写警告 |
| 线程 | 所有引擎回调 `post` 到同一个 `mainHandler`，串行化状态迁移 |

**实测**（真实通知，白名单内应用）：

```
20:41:38.539 queued: Eta。后台命令已完成。播报验证2
20:41:38.542 requestAudioFocus granted=true
20:41:38.579 speak result=0
20:41:38.582 TTS onStart s1-1-r0-p0
20:41:42.379 TTS onDone  s1-1-r0-p0
```

`onStart → onDone` 严格配对，token 格式正确，无重复回调、无守卫误触发。

### 8 module_seen 升级为「心跳 + 回执」

内容从单个秒级时间戳改成 `ts ver last_req result`（如 `1795783331 v1.17 1795783313949 fixed`），
同时当心跳和请求回执 —— 只靠 `onListenerConnected` 当回执有洞：
守护「复查后忽略」那条分支不会产生该回调，应用只看到沉默，分不清「模块没在」和「监听本来就好」。
写入后 `chown` 连组一起交还（原来只改 user，组仍是 root），并补 `restorecon`。

### 本轮尚未完成（按 Claude 排序）

1. `IMPORTANCE_MIN` 自检通知探针，替换「沉默多久就判死」的误判源头
2. 补扫 watermark：`lastProcessedPostTime` 落盘，补扫只处理比它新的、按 postTime 排序、限 3 条其余并入摘要
   （现在 `key+postTime` 不够：进程重启后 `announced` 集合丢失，补扫会把通知栏里没划掉的全念一遍）
3. 真实 boost → 延迟 400ms 还原的实测（需无耳机时段）
4. 状态机异常路径脚本化测试（抽 `TtsEngine` 接口 + 假引擎喂乱序/迟到/重复回调）
