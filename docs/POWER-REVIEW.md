# 功耗与检测策略评审

> 对象：`XWWP/HeadsetNotifier`（app `2.7.4` / keepalive 模块 `v1.2`，commit `新增 root 保活模块 v1.2`）
> 范围：`keepalive/service.sh`、`NotificationReaderService.java`、`KeepAliveService.java`、`ShizukuChannel.java`、`MainActivity.java`（仅功耗相关路径）
> 性质：**只评审，未改任何代码**
> 结论置信度标注：`[实测]` 已在本机 shell 语义/代码层面确认；`[推断]` 依现有代码与 AOSP 行为推导，需真机验证；`[待测]` 必须用真机数据定案。

---

## 0. TL;DR

| 问题 | 结论 |
| --- | --- |
| “一段时间没新通知就降低解冻频率”会不会更省电？ | **会，但只在误差级**：估算 0.02–0.1 Wh/天（≈ 整机电量的 0.1%–0.5%），且大头是唤醒而非 CPU。 |
| 有没有必要？ | **没有必要，而且方向是错的**。被冻结的进程无法上报“有新通知”，任何降频只能按时间猜；而“静默越久”恰恰意味着“下一条越可能是用户在等的那条”，延迟赔在最痛的地方。 |
| 模块本身还有省电余地吗？ | **有，而且比降频大得多**：① 陈旧标记导致**永久** 2 秒空转（bug）；② `low` 模式 24 小时常驻的 `dumpsys media.audio_policy`；③ 每 tick 4–5 次 fork；④ 蓝牙状态的误报会放大成 60 秒一次 `am start-foreground-service`；⑤ app 侧 5 秒一次的 `getActiveNotifications()` 与 1 秒一次的 `getDevices()`。 |

**建议顺序**：先修 §5.1 的正确性 bug → 再做 §5.2 的“零延迟成本”优化 → 然后跑 §7 的四个实验 → **最后由实验数据决定 2 秒要不要改**，而不是由“静默多久”决定。

---

## 1. 成本模型：2 秒循环到底花多少电

### 1.1 解冻本身不耗电

`thaw()` 写 `cgroup.freeze` 用的是 shell 内置重定向，**零 fork、零额外进程**：

```sh
if ! echo 0 >"$FREEZE_FILE" 2>/dev/null; then   # 内置命令，不 fork
```

真正的成本只有两项：**每 tick 的 fork/exec**，和**唤醒节律**。

### 1.2 每 tick 的 fork 次数 `[实测：代码语义]`

高频（`high`）模式一拍的实际 fork：

| 动作 | 代码 | fork 次数 |
| --- | --- | --- |
| 取 pid | `PID=$(app_pid)` → 命令替换子壳 + `pidof` | 2 |
| 读冻结状态 | `[ "$(cat "$FREEZE_FILE")" = "1" ]` | 1 |
| 日志节流（仅解冻时） | `NOW=$(date +%s)` | 1 |
| 休眠 | `sleep 2` | 1 |
| **合计** | | **4–5 次 / 2 秒** |

即 **17–22 万次 fork/天**。单次 toybox fork+exec 约 1–3 ms CPU ⇒ **260–430 CPU·秒/天 ≈ 单核 0.3%–0.5%**，折算 **0.03–0.1 Wh/天**（5000 mAh ≈ 19 Wh）。

`low` 模式一拍（20 秒）：`app_pid` 2 + `dumpsys` 1 + 管道里 1–2 个 `grep` + `sleep` 1 ⇒ 5–6 次 fork + **一次完整 `dumpsys media.audio_policy`**。

### 1.3 唤醒节律才是贵的那一半 `[推断]`

2 秒周期意味着在“耳机戴着、屏幕灭、没在放歌”的窗口里，SoC 最多每 2 秒进出一次 s2idle，一夜上限约 **4.3 万次**。每次 suspend/resume 的能量远高于那几毫秒 CPU。

> 注意一个反直觉后果：如果 suspend 真的把 `sleep 2` 推迟了，这个循环**本来就没有 2 秒精度**——既没拿到精度，又付了抖动的电费。这一点必须先实测（§7.1）。

### 1.4 汇总估算 `[推断]`

| 项 | 频次 | 每天次数 | 量级 |
| --- | --- | --- | --- |
| 高频循环 fork | 0.5 Hz | 17–22 万 | 0.03–0.1 Wh |
| suspend 抖动 | ≤0.5 Hz | ≤4.3 万/夜 | 0.05–0.3 Wh |
| `low` 模式 dumpsys | 1/20 s | **4320（24h 常驻）** | 130–430 CPU·秒 |
| app `getActiveNotifications()` | 1/5 s | **1.7 万** | 跨进程 + 全量 Notification 打包 |
| app `getDevices()`（蓝牙判定） | ≈1/5 s | 1.7 万 | 每次返回设备数组 |
| 心跳写 prefs（含 fsync） | 1/60 s | 1440 | 微量 |

**总盘子：耳机在线的几小时里约为整机的 0.3%–1%；整夜空挂耳机时可能到 1%–3%。**

在这个量级上再加一套“自适应降频”状态机，收益与复杂度不匹配。

---

## 2. 为什么“按静默时长降频”在原理上不成立

### 2.1 信号不可观测

模块能看到的只有 `pidof` 的结果和 `cgroup.freeze` 的值。**被冻结的进程无法上报“有新通知”**，所以“要不要解冻”这件事，只能靠解冻本身去问。任何降频都只能是**时间驱动**的猜测。

### 2.2 代理指标方向是反的

“静默越久 → 下一条越可能是用户在等的那条（微信/QQ 的第一条）”。降频把延迟直接加在这条上：

- 静默 10 分钟 → 退到 30 秒一拍 → **第一条消息最多延迟 30 秒**；
- 而一条 TTS 播报本身只要 3–8 秒。

用 0.05 Wh 换“晚半分钟开口”，用户感知是明确的净损失。

### 2.3 降频只动唤醒，不动 CPU 时间

2 秒 → 10 秒，fork 总数只降到 1/5（省 0.02–0.08 Wh）；若有 80% 概率循环本来就被 suspend 推迟，则连这点也省不到。

### 2.4 需要高频的时刻恰好是“播报中”

`onStart/onDone/onError` 回调、音量恢复、队列 drain 全靠这些回调落到进程里。静默期降频、播报期升频，等于**在唯一需要保护的时刻之外都在保护**。

> **结论：不做时间驱动的降频。**

---

## 3. 正确的降频：按“冻结是否真的发生”降频

这是可观测信号，且**零延迟代价**：

- 连续 N 个 tick 读到 `cgroup.freeze == 0` → 这台 ROM/这个用户根本没有在冻它（或一次性豁免生效了），2 秒纯属空转 → 退到 20 秒；
- 一旦读到 `1` → 立刻回 2 秒并保持（说明冻结是真的，高频才有意义）。

```sh
# 伪代码（未实现）
IDLE_TICKS=0
...
if [ "$FROZEN" = "1" ]; then
    IDLE_TICKS=0
    INTERVAL=2
    thaw
else
    IDLE_TICKS=$((IDLE_TICKS + 1))
    [ "$IDLE_TICKS" -ge 60 ] && INTERVAL=20      # 连续 2 分钟没冻结过
fi
sleep "$INTERVAL"
```

这条规则**不会在最坏情况下增加延迟**（只要它在冻，就一直是 2 秒），收益只落在“本来就不需要保护”的场景。§7.1 的实验会直接告诉我们该不该加。

---

## 4. 现有机制的正确性审查

### 4.1 `thaw` 的有效期是未知数 `[待测]`

`CachedAppOptimizer` 的重冻结由 `freeze_debounce_timeout` 等参数驱动。若它是**事件驱动**（procstate 变化才评估），一次解冻可能维持很久，那 2 秒轮询就是纯浪费；若它每秒都重新扫描，2 秒就是必要的。**这个参数直接决定 2 秒该不该存在**，必须实测（§7.1）。

### 4.2 解冻窗口只有约 50% 占空比 `[推断]`

如果 ROM 在 ~1 秒内把进程重新冻上，那么每 2 秒里 app 只有约 1 秒能跑。推论：

- 系统回调（binder 事务在冻结期间被 driver 排队）会在窗口内投递，延迟 ≤2 秒——这是 2 秒这个数字的真正来源，也是它**有效**的原因；
- 同时也意味着 app 侧的 CPU 时间是减半的，**所以别把 app 的 5 秒轮询算成满负荷**。

### 4.3 冷启动竞态

`freeze_file_of()` 只在 pid 变化时解析，失败后每 30 tick 重试一次。开机早期 cgroup 目录未就绪的场景已被考虑 `[实测：代码]`。若进程被重新 fork 但 `/proc` 短暂不可读，会出现一次空窗，可接受。

---

## 5. 问题清单

### 5.1 P0 — 是 bug，不是策略

#### P0-1 陈旧标记导致永久高频空转（**最该先修**）

`service.sh:209-211`：

```sh
elif [ "$FG_FAIL" -lt 2 ]; then
    FG_FAIL=$((FG_FAIL + 1))
    [ "$FG_FAIL" -ge 2 ] && log "前台服务复查失败：保护标记可能已过期"
fi
```

**只记账、只打日志，从不降级。** 触发路径：用户划掉 App / 系统杀进程 → `KeepAliveService.onDestroy()` 没走到 → `files/active` 残留 → 模块永远 2 秒一拍，而 `thaw()` 每次都在 `[ -f "$FREEZE_FILE" ]` 处空返回。

整夜白跑 4 万次唤醒，**这才是用户会感知成“这模块费电”的场景。**

建议：`FG_FAIL >= 3` 时回 `low` 并清标记；标记本身改为“活体信号”（见 P0-2）。

```sh
elif [ "$FG_FAIL" -lt 3 ]; then
    FG_FAIL=$((FG_FAIL + 1))
    if [ "$FG_FAIL" -ge 3 ]; then
        log "前台服务连续 3 次复查失败，判定标记陈旧：清标记并退回低频"
        rm -f "$FLAG"
        MODE=low
        FG_FAIL=0
        sleep 20
        continue
    fi
fi
```

#### P0-2 模块在删 app 拥有的文件，存在“永久静默”路径

写者二义：`KeepAliveService.onCreate()`（`createNewFile()`）与 `service.sh:197`（`rm -f "$FLAG"`），中间没有任何所有权协议。

已知可构造的坏路径：用户在 FGS 仍存活时快速“关 → 开”总开关（或模块因一次误判删掉标记），`onCreate` 不会重跑，标记就再也不会被创建 → 模块停在 `low` → app 被冻结 → **播报静默失效，直到重启 App**。

建议：

1. 标记文件归 app 独占，模块**只读不删**；模块要“停”，就把本地 `MODE` 置 `low`。
2. 标记内容带上活体时间戳（app 每次成功播报 `touch` 一次），模块用 `stat -c %Y`（一次 syscall，零 fork）判断“这个标记还活着吗”。

#### P0-3 `bt_audio_active()` 误报会放大成 24 小时开机唤醒

`service.sh:89-101`：

```sh
DUMP=$(dumpsys media.audio_policy 2>/dev/null)
if printf '%s\n' "$DUMP" | grep -qE 'AUDIO_DEVICE_OUT_(BLUETOOTH_A2DP|BLUETOOTH_SCO|BLE_HEADSET|BLE_SPEAKER)'; then
    return 0
fi
case "$DUMP" in *"Available output devices"*) return 1 ;; esac
```

多个 Android 版本的 `dumpsys media.audio_policy` 会输出**设备连接表**（包含未连接的 profile）。一旦误报为“有耳机”，就会每 60 秒执行 `am start-foreground-service` + `sleep 3`（`wake_app()`，`service.sh:142-158`）——这是整个模块里最重的动作（AMS 事务 + 进程优先级变更），**比 2 秒循环贵一个数量级**，而且会挂一条常驻通知。

判定方法见 §7.3。建议改成精确匹配“当前已连接输出”所在的段落，而不是全文 grep。

### 5.2 P1 — 免费的电（改动等价、零延迟代价）

| 编号 | 位置 | 问题 | 建议 | 收益 |
| --- | --- | --- | --- | --- |
| P1-1 | `service.sh:123-140` | `$(cat)` + `$(date +%s)` 每 tick 两次 fork | `read` 与 `/proc/uptime` 取代（见下方代码） | 每 tick −2 fork（−40%） |
| P1-2 | `service.sh:90` | `low` 模式 20 秒一次全量 `dumpsys`，**24 小时常驻** | 命中“无耳机”后做负缓存（如 2 分钟内不重复 dump）；或只在亮屏/刚开机时做全量探测 | 砍掉模块日耗电的最大一项 |
| P1-3 | `service.sh:160-161` | 每 60 秒一次 `dumpsys activity services`（整份 dump 里 grep 一行） | 先判 `/proc/$PID/cmdline` 存活，可疑了再 dump | −1440 次/天全量 dump |
| P1-4 | `NotificationReaderService.java:395-400` | `isBluetoothConnected()` 每 1 秒过期 → 约 5 秒一次 `getDevices()` binder | `audioDeviceCallback`（已注册，`:297`）维护布尔量，只在回调里更新 | −1.7 万次 binder/天 |
| P1-5 | `NotificationReaderService.java:193-207` | 心跳 60 秒一次 `SharedPreferences.apply()` = 每天 1440 次“重写 XML + fsync” | 5 分钟一次，或写 `files/heartbeat` 一行时间戳（顺便当 P0-2 的活体信号） | 少 1200+ 次 fsync/天 |
| P1-6 | `NotificationReaderService.java:405-409` | TTS 只建不拆：`shutdown()` 只在 `onDestroy`（`:795`），拔耳机后引擎仍被 bind 常驻 | `audioDeviceCallback.onAudioDevicesRemoved` 里 `shutdown()` 并置 `null` | 释放引擎进程常驻内存 |
| P1-7 | `NotificationReaderService.java:64` 注释 vs `MainActivity.java:57` | 注释写“默认 1.5 秒”，实际 `DEFAULT_POLL_INTERVAL_MS = 5000` | 修注释；并考虑“KeepAliveService 在跑 → 默认 15 秒”（回调路径已通，轮询只当兜底） | 文档一致性 + 轮询降频 |

P1-1 的等价改法（**行为不变，只去 fork**）：

```sh
# 改前
[ "$(cat "$FREEZE_FILE" 2>/dev/null)" = "1" ] || return 0
NOW=$(date +%s)

# 改后：read 与 /proc/uptime 都是内核接口 + 内置命令，零 fork
read -r FROZEN <"$FREEZE_FILE" 2>/dev/null || return 0
[ "$FROZEN" = "1" ] || return 0
read -r UP _ </proc/uptime            # 含 suspend 时间，单调
NOW=${UP%.*}
```

> `PID=$(app_pid)`（2 fork）暂不建议动：去掉后必须处理 **PID 复用**（旧 pid 被别的进程占用会让 `FREEZE_FILE` 指向错误 cgroup，进而“以为在解冻、实际没解”）。若要做，必须用 `/proc/$PID/cgroup` 里的 `uid_XXXXX` 做交叉校验。

### 5.3 P2 — 架构上更彻底的一刀（先验证再动）

你有 root，`cgroup.freeze` 是兜底而不是第一选择。应在开机时**一次性**做：

```sh
am set-standby-bucket com.sakura.airannounce active
cmd deviceidle whitelist +com.sakura.airannounce
cmd appops set com.sakura.airannounce RUN_ANY_IN_BACKGROUND allow
```

`ShizukuChannel.applyQuietlyIfEnabled()` 里已有前两条，但**只在用户手动开启 Shizuku 增强时才跑**（`ShizukuChannel.java:174`）。root 模块里应当无条件跑一遍，并每天重放（bucket 会衰减）。

然后用 §7.2 观察 10 分钟 `cgroup.freeze`：

- **不再变 1** → 整个 2 秒循环可以退休，功耗问题从根上消失；
- **仍然变 1** → 说明 ColorOS 的 `Athena`/`OplusHansManager` 无视这些设置（README 的描述暗示了这一点），那轮询才是必要兜底，届时再谈 §3 的自适应。

---

## 6. 建议的实施顺序

| 顺序 | 改动 | 收益 | 风险 | 工作量 |
| --- | --- | --- | --- | --- |
| 1 | P0-1 陈旧标记降级 + 清标记 | 消除“永久高频空转”，用户可感知 | 低（需确认 FG 复查本身不误判） | 10 行 |
| 2 | P1-1 `read` / `/proc/uptime` | 每 tick 成本 −40% | 极低（语义等价） | 6 行 |
| 3 | P1-4 复用 `audioDeviceCallback` | −1.7 万次 binder/天 | 低（注意回调线程） | 20 行 |
| 4 | P0-3 精确匹配 `bt_audio_active` | 避免 24h 误唤醒 | 中（需真机 dump 样本） | 20 行 |
| 5 | P1-2 `low` 模式负缓存 | 砍掉最大常驻项 | 中（影响耳机识别时延） | 30 行 |
| 6 | P2 一次性 root 豁免 + 验证 | 可能整体退休轮询 | 中（不同 ROM 差异大） | 先测 |
| 7 | §3 自适应（可选） | 只在“不冻结”场景省电 | 低 | 15 行 |

**不建议做**：按“最后一次播报距今多久”降频（§2）。

---

## 7. 实测清单（比读代码更能定案）

### 7.1 重冻结时延 ← 决定 2 秒是否必要

```sh
PID=$(pidof com.sakura.airannounce)
F=/sys/fs/cgroup$(sed -n 's/^0:://p' /proc/$PID/cgroup)/cgroup.freeze
echo 0 > "$F"
for i in $(seq 1 40); do echo "$(date +%T) $(cat "$F")"; sleep 1; done
```

| 现象 | 判读 |
| --- | --- |
| 1–3 秒内变回 `1` | 2 秒是必要的，**不要动**（且说明重冻结是 debounce 驱动） |
| 10 秒仍为 `0` | 你正在“每秒 2 次给不需要的东西续命”，可退到 10–30 秒 |
| 一直为 `0` | 该 ROM 根本没冻结它 → 走 §5.3，循环可退休 |

### 7.2 一次性豁免是否有效

```sh
am set-standby-bucket com.sakura.airannounce active
cmd deviceidle whitelist +com.sakura.airannounce
# 然后每 10 秒采样一次 cgroup.freeze，共 10 分钟
for i in $(seq 1 60); do echo "$(date +%T) $(cat "$F")"; sleep 10; done
```

出现 `1` ⇒ 豁免无效，轮询是必要兜底。

### 7.3 `bt_audio_active` 是否误报

```sh
dumpsys media.audio_policy | wc -c                       # 先看这份 dump 多大
dumpsys media.audio_policy | grep -nE 'AUDIO_DEVICE_OUT_(BLUETOOTH|BLE)'
# 关键：拔掉耳机后再跑一遍，若仍能 grep 到就是误报
```

若误报，把 §5.1 P0-3 的匹配改成“已连接输出设备”段落内的精确匹配。

### 7.4 整机基线（唯一能证伪“更省电”的东西）

```sh
dumpsys batterystats --reset
# 飞行模式 + 连耳机 + 息屏，静置 8 小时
dumpsys batterystats --charged | grep -iE 'com.sakura|toybox|/system/bin/sh'
dumpsys power | grep -iE 'suspend|wake'      # 或 cat /sys/power/suspend_stats/*
```

对比“模块启用 / 停用”两组，看 `mAh` 与 suspend 次数。**没有这个基线，任何“更省电”都是猜。**

---

## 8. 本报告无法验证的假设（诚实声明）

1. **重冻结的真实时延**（§4.1）——没有真机数据，2 秒的“必要性”目前只是代码意图，不是观测量。
2. **`dumpsys media.audio_policy` 在 ColorOS/一加上的输出格式**是否含未连接设备——直接决定 P0-3 是否成立。
3. **每 tick fork 的耗时**（1–3 ms）是 ARM64 + toybox 的经验区间，未在你的设备上取样。
4. **suspend 是否真的被 2 秒周期打断**——取决于内核 `s2idle` 进入条件与厂商 ROM 的 wakeup 策略，代码层面无法判定。
5. **ColorOS 的冻结是否忽略 standby bucket / doze 白名单**——README 的措辞暗示“是”，但未验证。

---

## 9. 附：低优先级观察（不在本次改动范围）

- `service.sh:40-47`：单实例判定用 `grep -qa "service.sh" /proc/$OLD/cmdline`，PID 复用时可能误杀无关进程（概率极低）。
- `service.sh`：未注册 `trap` 清理 `PIDFILE`，异常退出后残留一个旧 pid 文件（下次启动会用 `grep` 校验，无害）。
- `service.sh:105-121`：`FREEZE_FILE` 按 pid 缓存；若进程发生 cgroup 迁移不会重解析（Android 上基本不发生）。
- `NotificationReaderService.java:503`：`MainActivity.isForeground` 时轮询直接 `return`，但 `finally` 仍会排下一次；语义正确，只是这一拍做了两次 prefs 读。
- `MainActivity.onResume()`（`:149-165`）在一次回调里连续调用 `hasBluetoothAudioOutput()` 三次（`permissionSnapshot` / `updateStatus` / `syncKeepAlive`），可合并成一次并复用 P1-4 的缓存。
- `keepalive/module.prop` 的 description 与 README 都写死“每 2 秒”，若日后做 §3 自适应，需同步改文案。

---

## 10. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-05 | 首版：针对“静默降频是否省电”的评审，含 P0/P1/P2 清单与实测方案 |

> 本文件不含任何代码改动。若采纳，建议放在仓库根目录，与 `HANDOVER.md` 并列。
