# 功耗评审处置记录（2.7.5）

对应评审：`docs/POWER-REVIEW.md`（评审对象 2.7.4 + keepalive v1.2，commit `79bc560`）
本轮改动版本：`2.7.5`（versionCode 22）

> 约定：每条都写清「采纳 / 部分采纳 / 不采纳」和理由。不采纳的也保留理由与后续验证方式，避免下一轮重复讨论。

## 一句话结论

评审的**主判断成立**：按「静默时长」降频方向错误、收益在误差级；真正该修的是零延迟代价的正确性问题和免费的电。本轮采纳 P0-1 / P0-2 / P1-1 / P1-3 / P1-4 / P1-5 / P1-6 / P1-7；P0-3 在本机未复现，暂时不动；P1-2、P2、§3 需要真机数据，先放着。

## 逐条处置

| 编号 | 结论 | 实际改动 |
| --- | --- | --- |
| P0-1 陈旧标记导致永久高频空转 | **采纳** | `service.sh`：`FG_FAIL` 累计到 3 次（约 1 分钟）即判定标记陈旧，`STALE_BLOCK=1` 并退回 `low`，不再 2 秒空转。原来只打一行日志、之后什么都不做，这条确实是 bug。 |
| P0-2 模块删 app 的文件 → 可能永久静默 | **采纳** | 模块改为**只读不删**标记，需要"停"时只把本地 `MODE` 置 `low`；标记的活体信号用 mtime 实现（一次 `stat -c %Y`，零 fork）。app 侧 `KeepAliveService.onStartCommand()` 每次拉起都 `touch` 标记，`onCreate` 也改走同一个方法。这样「模块判陈旧 → app 下次拉起刷新 mtime → 模块自动恢复高频」形成闭环，不需要人工重启应用。 |
| P0-3 `bt_audio_active` 误报 | **不采纳（本机未复现）** | 本机实测：**未连接任何耳机**时 `dumpsys media.audio_policy` 中 `AUDIO_DEVICE_OUT_BLUETOOTH* / AUDIO_DEVICE_OUT_BLE*` 命中数为 **0**，`Available output devices (3)` 只列 `earpiece` 等，确实只列已连接输出。也就是说这台 ColorOS 的 dump 里不含"未连接的 profile 表"，当前匹配方式不会误报。保留原实现，等真机连耳机样本再判断；若拿到反例再按"已连接输出段落内精确匹配"改。 |
| P1-1 `$(cat)` / `$(date +%s)` 每 tick 两次 fork | **采纳** | `thaw()` 改用 `read -r FROZEN <"$FREEZE_FILE"` 与 `read -r UP _ </proc/uptime`，`/proc/uptime` 含 suspend 时间且单调，语义等价、零 fork。 |
| P1-2 `low` 模式 20 秒一次全量 dumpsys | **不采纳（暂缓）** | 评审给的负缓存 2 分钟会直接把「戴上耳机到被识别」的最坏时延拉到 2 分钟，与"戴上就能播"直接冲突，我认为这笔交易不划算。收益是 24 小时约 4320 次 dumpsys，功耗量级约 0.02–0.03 Wh/天。等 §7.4 的整机基线数据出来再决定要不要换成 30–60 秒的短负缓存。 |
| P1-3 每 60 秒一次全量 `dumpsys activity services` | **采纳** | `foreground_ok()` 先判 `/proc/$PID/cmdline` 可读，进程不在就直接返回失败，不跑 dump。 |
| P1-4 蓝牙状态每 5 秒一次 `getDevices()` | **采纳** | 状态改由 `audioDeviceCallback` 维护（新增 `onAudioDevicesAdded`，`onAudioDevicesRemoved` 同时刷新），另加 10 分钟兜底重查，防止个别 ROM 不发设备回调。一天少掉约 1.7 万次 binder 调用。 |
| P1-5 心跳 60 秒一次 prefs fsync | **采纳** | 写盘间隔 60s → 180s；**同时把断档阈值 180s → 420s**，否则放宽写盘会立刻误报"疑似中断"。 |
| P1-6 TTS 只建不拆 | **采纳** | `onAudioDevicesRemoved` 里在 `stop()` 之后直接 `shutdown()` 并置空、`ttsReady=false`，下次戴上耳机由 `ensureTtsInitialized()` 重建。代价是拔了再戴的第一条播报多几百毫秒初始化。 |
| P1-7 注释与默认轮询值不一致 | **部分采纳** | 注释已改为「默认 5 秒（1–30 秒可选）」。**没有**把默认值改成 15 秒——「KeepAliveService 在跑就降频」需要先确认回调路径在 ColorOS 上稳定，属于行为变更，留给实测后单独做。 |
| P2 一次性 root 豁免（standby bucket / deviceidle / appops） | **未做** | 需要先按 §7.2 验证 ColorOS 是否真的无视这些设置；没数据就改会在"以为豁免了、其实还在冻"的错觉下退掉轮询。`ShizukuChannel.applyQuietlyIfEnabled()` 里已有前两条，可直接复用。 |
| §3 按"是否真的发生冻结"自适应 | **未做** | 依赖 §7.1 的重冻结时延数据。`STALE_BLOCK` 机制已经解决了"根本没有保护对象还高频空转"的主要浪费，自适应剩下的是长尾优化。 |

## 本轮没有验证的部分（诚实声明）

1. **重冻结时延（§7.1）**：仍然没有真机数据，所以「2 秒是否必要」这个核心问题**没有结论**。本轮只修了"不需要保护时还在空转"和"每 tick 的固定开销"，没有动 2 秒这个节奏。
2. **一次性豁免是否有效（§7.2）**：没测。
3. **P0-3 的结论只覆盖"未连接耳机"这一种状态**：连上耳机后 dump 的形态还没采样。
4. **拔耳机 `shutdown()` TTS 对首条播报延迟的影响**：需要真机计时。

## 给下一轮的最低验证清单

```sh
# 1) 重冻结时延：决定 2 秒要不要动
PID=$(pidof com.sakura.airannounce)
F=/sys/fs/cgroup$(sed -n 's/^0:://p' /proc/$PID/cgroup)/cgroup.freeze
echo 0 > "$F"; for i in $(seq 1 40); do echo "$(date +%T) $(cat "$F")"; sleep 1; done

# 2) 一次性豁免是否有效
am set-standby-bucket com.sakura.airannounce active
cmd deviceidle whitelist +com.sakura.airannounce
# 然后每 10 秒采样一次 cgroup.freeze，共 10 分钟，出现 1 即豁免无效

# 3) bt_audio_active 在"连着耳机"时的 dump 形态
dumpsys media.audio_policy | grep -nE 'AUDIO_DEVICE_OUT_(BLUETOOTH|BLE)'

# 4) 模块实际节奏（确认 STALE_BLOCK 生效）
tail -50 /data/adb/modules/airannounce-thaw/keepalive.log
```
