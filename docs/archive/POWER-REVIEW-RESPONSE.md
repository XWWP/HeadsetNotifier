# 功耗评审处置记录（2.7.5）

对应评审：`docs/POWER-REVIEW.md`（评审对象 2.7.4 + keepalive v1.2，commit `79bc560`）
本轮改动版本：`2.7.5`（versionCode 22）

> 约定：每条都写清「采纳 / 部分采纳 / 不采纳」和理由。不采纳的也保留理由与后续验证方式，避免下一轮重复讨论。

## 一句话结论

评审的**主判断成立**：按「静默时长」降频方向错误、收益在误差级；真正该修的是零延迟代价的正确性问题和免费的电。本轮采纳 P0-1 / P0-2 / P1-1 / P1-3 / P1-4 / P1-5 / P1-6 / P1-7；P0-3 曾误判为未复现，复核后确认成立并已修复；P1-2、P2、§3 需要真机数据，先放着。

## 逐条处置

| 编号 | 结论 | 实际改动 |
| --- | --- | --- |
| P0-1 陈旧标记导致永久高频空转 | **采纳** | `service.sh`：`FG_FAIL` 累计到 3 次（复查每分钟一次，约 3 分钟）即判定标记陈旧，`STALE_BLOCK=1` 并退回 `low`，不再高频空转。原来只打一行日志、之后什么都不做，这条确实是 bug。 |
| P0-2 模块删 app 的文件 → 可能永久静默 | **采纳** | 模块改为**只读不删**标记，需要"停"时只把本地 `MODE` 置 `low`；标记的活体信号用 mtime 实现（一次 `stat -c %Y`，零 fork）。app 侧 `KeepAliveService.onStartCommand()` 每次拉起都 `touch` 标记，`onCreate` 也改走同一个方法。这样「模块判陈旧 → app 下次拉起刷新 mtime → 模块自动恢复高频」形成闭环，不需要人工重启应用。 |
| P0-3 `bt_audio_active` 误报 | **采纳（此前判断错误，已更正）** | 我方先前记为「本机未复现」，是因为只看了 `Available output devices` 段里只列 `earpiece`，没去数全文命中。复核发现：未连任何耳机时，`dumpsys media.audio_policy` 全文命中 `AUDIO_DEVICE_OUT_BLUETOOTH*` / `BLE*` 达 **145 次（后续复测 172 次）**，来源是 `Hardware modules` 段里的 HAL 能力表，形如 `{"bt_a2dp_out"; {AUDIO_DEVICE_OUT_BLUETOOTH_A2DP, @:}}`——`@:` 后面为空。于是每 20 秒都会认为“有耳机”并拉起前台服务。改为只在 `Available output devices` 段内匹配 `^  [0-9]+\. Port ID:` 端口行（真正相连的端口带 MAC，如 `@:18:5C:A1:...`），并用构造样本验证：删掉段内蓝牙端口、保留能力表时判 `none`；真机连耳机时判 `bt`；dump 无该段时回落 `bluetooth_manager`。 |
| P1-1 `$(cat)` / `$(date +%s)` 每 tick 两次 fork | **采纳** | `thaw()` 改用 `read -r FROZEN <"$FREEZE_FILE"` 与 `read -r UP _ </proc/uptime`，`/proc/uptime` 含 suspend 时间且单调，语义等价、零 fork。 |
| P1-2 `low` 模式 20 秒一次全量 dumpsys | **不采纳（暂缓）** | 评审给的负缓存 2 分钟会直接把「戴上耳机到被识别」的最坏时延拉到 2 分钟，与"戴上就能播"直接冲突，我认为这笔交易不划算。收益是 24 小时约 4320 次 dumpsys，功耗量级约 0.02–0.03 Wh/天。等 §7.4 的整机基线数据出来再决定要不要换成 30–60 秒的短负缓存。 |
| P1-3 每 60 秒一次全量 `dumpsys activity services` | **采纳** | `foreground_ok()` 先判 `/proc/$PID/cmdline` 可读，进程不在就直接返回失败，不跑 dump。 |
| P1-4 蓝牙状态每 5 秒一次 `getDevices()` | **采纳** | 状态改由 `audioDeviceCallback` 维护（新增 `onAudioDevicesAdded`，`onAudioDevicesRemoved` 同时刷新），另加 10 分钟兜底重查，防止个别 ROM 不发设备回调。一天少掉约 1.7 万次 binder 调用。 |
| P1-5 心跳 60 秒一次 prefs fsync | **采纳** | 写盘间隔 60s → 180s；**同时把断档阈值 180s → 420s**，否则放宽写盘会立刻误报"疑似中断"。 |
| P1-6 TTS 只建不拆 | **采纳** | `onAudioDevicesRemoved` 里在 `stop()` 之后直接 `shutdown()` 并置空、`ttsReady=false`，下次戴上耳机由 `ensureTtsInitialized()` 重建。代价是拔了再戴的第一条播报多几百毫秒初始化。 |
| P1-7 注释与默认轮询值不一致 | **部分采纳** | 注释已改为「默认 5 秒（1–30 秒可选）」。**没有**把默认值改成 15 秒——「KeepAliveService 在跑就降频」需要先确认回调路径在 ColorOS 上稳定，属于行为变更，留给实测后单独做。 |
| P2 一次性 root 豁免（standby bucket / deviceidle / appops） | **未做** | 需要先按 §7.2 验证 ColorOS 是否真的无视这些设置；没数据就改会在"以为豁免了、其实还在冻"的错觉下退掉轮询。`ShizukuChannel.applyQuietlyIfEnabled()` 里已有前两条，可直接复用。 |
| §3 按"是否真的发生冻结"自适应 | **未做** | §7.1 的数据已拿到（见 `MEASUREMENT-REFREEZE.md`）。`STALE_BLOCK` 机制已经解决了"根本没有保护对象还高频空转"的主要浪费，自适应剩下的是长尾优化。 |

## 本轮没有验证的部分（诚实声明）

1. ~~**重冻结时延（§7.1）**~~ **测了，但没有测出结论**（`docs/MEASUREMENT-REFREEZE.md`、`docs/MEASUREMENT-INTERVAL.md`）。早期一轮（86 样本 / 11 分钟）观察到 2 次重冻结且均 ≤5 秒自解；后续四组对照（亮屏/息屏 × 高频/低频，共约 40 分钟、1100+ 样本）**一次冻结都没出现**，所以"5 秒够不够快"无法用数据回答，5 秒只能算"成本更低、收益未被证伪"的保守值。另需更正：早期把"样本平均间隔 7.7 秒"解释为"sleep 无精度"是错的——本轮设定 5 秒实测平均 5.0 秒、设定 2 秒实测 2.0 秒，`sleep` 本身是准的；7.7 秒更可能来自采样脚本与被测循环的相位错配或固定开销占比。
2. **一次性豁免是否有效（§7.2）**：没测。
3. ~~**P0-3 只覆盖“未连接耳机”一种状态**~~ **已采样两种状态**：未连耳机（全文命中 145/172 次全来自能力表）与连耳机（`Available output devices` 段内出现带 MAC 的 `bt_sco_headset` / `BT A2DP Out`），并据以重写匹配逻辑。
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
