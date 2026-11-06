# HeadsetNotifier 修改意见（基于 TAKEOVER.md 与自检报告）

日期：2026-11-06（v2：加入版本门控与修复降级）
对象：app 2.7.8 / versionCode 25，root 模块 v1.6 / versionCode 7
依据文档：`HANDOVER.md`、`docs/TAKEOVER.md`、`docs/SELF-CHECK-2026-11-06.md`

---

## 一、核心问题

**P0-1：重装/升级 APK 后通知监听不会自动重绑**

- 现象：授权字符串正常，但系统不建立绑定，`Live notification listeners` 里没有本应用，播报完全静默，界面无告警。
- 现状：每次需手动执行 `cmd notification disallow_listener ... && cmd notification allow_listener ...`。
- P1-3（模块只查进程存在、不查监听健康）是同一问题的另一面：进程活着但监听死了，模块不做任何事（实测静默失效 80 分钟）。

**设计约束**（TAKEOVER.md 已明确，方案遵守）：

- shell 脚本没有 binder 客户端，做不了真事件回调 → 用「app 写文件当事件源，模块 stat 文件当检查」，成本从几十 KB 的 dumpsys 降到一次 stat；
- 不用「每 5 分钟 dumpsys notification」这种重轮询；
- `files/active` 的 mtime 语义不动（TAKEOVER P1-2 结论），健康状态另开文件。

---

## 二、方案：app 侧事件源 + 模块侧修复

### 2.1 App 侧：`files/listener_connected` 绑定状态信标

文件：`app/src/main/java/com/sakura/airannounce/NotificationReaderService.java`

改动三处：

| 位置 | 动作 |
|---|---|
| `onListenerConnected()`（约 L359）末尾 | 调 `writeListenerMarker()` |
| `onListenerDisconnected()`（约 L370） | 调 `deleteListenerMarker()` |
| `onDestroy()`（约 L803） | 调 `deleteListenerMarker()` |

```java
private void writeListenerMarker() {
    try {
        java.io.File f = new java.io.File(getFilesDir(), "listener_connected");
        // 内容写当前 PID：进程被 SIGKILL 后来不及删文件时，
        // 模块靠「文件里的 PID ≠ 当前进程 PID」识别陈旧标记，避免假阳性
        java.nio.file.Files.write(f.toPath(),
                String.valueOf(android.os.Process.myPid()).getBytes());
    } catch (Exception ignored) { }
}

private void deleteListenerMarker() {
    try {
        new java.io.File(getFilesDir(), "listener_connected").delete();
    } catch (Exception ignored) { }
}
```

**为什么内容要存 PID**（对 TAKEOVER 建议的补充）：进程被 SIGKILL 时 `onDestroy` 不一定执行，残留文件会让模块误以为监听还活着。PID 比对堵死这个假阳性，是纯文件方案能做到的最强语义。

### 2.2 模块侧：`keepalive/service.sh` 增加绑定检查（含版本门控与降级）

新增变量与函数：

```sh
LISTENER_FLAG=$DATA/files/listener_connected
LISTENER_FIX_AT=0          # 冷却：重绑最多 5 分钟一次
LISTENER_FIX_FAIL=0        # 连续修复无效次数（≥3 停手）
LISTENER_APP_OK=-1         # 版本门控缓存：-1 未探测 / 0 太旧 / 1 支持
PID_SEEN_AT=0              # 首次看到当前 PID 的时间

listener_ok() {
    [ -n "$PID" ] || return 1
    read -r FPID <"$LISTENER_FLAG" 2>/dev/null || return 1
    [ "$FPID" = "$PID" ]
}

# 版本门控：app ≥ 2.7.9（versionCode 26）才有信标逻辑。
# 没有门控的话，旧版本永远没有信标，模块会把「不支持」误判成「未绑定」，
# 每 5 分钟对正常工作的旧 app 强制执行一次断开重绑——比不修还糟。
listener_feature_supported() {
    if [ "$LISTENER_APP_OK" = "-1" ]; then
        VC=$(dumpsys package "$PKG" 2>/dev/null \
             | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n1)
        if [ -n "$VC" ] && [ "$VC" -ge 26 ]; then
            LISTENER_APP_OK=1
            log "监听自愈已启用（app versionCode=$VC）"
        else
            LISTENER_APP_OK=0
            log "app versionCode=${VC:-未知} < 26，跳过监听自愈（需 app ≥ 2.7.9）"
        fi
    fi
    [ "$LISTENER_APP_OK" = "1" ]
}

# 修复前复核：信标缺失 ≠ 真的没绑定（写文件失败、空窗期都会假缺失）。
# dumpsys 只在疑似故障时跑一次，不进常规循环，成本可接受。
listener_really_missing() {
    ! dumpsys notification 2>/dev/null \
        | sed -n '/Live notification listeners/,/Snoozed/p' \
        | grep -q "$PKG"
}

fix_listener() {
    listener_feature_supported || return 0
    [ "$LISTENER_FIX_FAIL" -lt 3 ] || return 0     # 连续 3 次无效：停手等人工
    NOW=$(date +%s)
    [ $((NOW - PID_SEEN_AT)) -ge 30 ] || return 0  # 宽限：绑定本来就要时间
    [ $((NOW - LISTENER_FIX_AT)) -ge 300 ] || return 0
    listener_really_missing || { LISTENER_FIX_FAIL=0; return 0; }
    LISTENER_FIX_AT=$NOW
    C="$PKG/$PKG.NotificationReaderService"
    cmd notification disallow_listener "$C" 0
    cmd notification allow_listener "$C" 0
    LISTENER_FIX_FAIL=$((LISTENER_FIX_FAIL + 1))
    log "监听未绑定（pid=$PID），已执行 disallow+allow 重绑（第 $LISTENER_FIX_FAIL/3 次）"
    if [ "$LISTENER_FIX_FAIL" -ge 3 ]; then
        log "连续 3 次修复无效，停止自动修复，需人工介入（系统设置里重开通知使用权，或重装应用）"
    fi
}
```

接入点（四处）：

1. **PID 变化时**：现有 `if [ "$PID" != "$FREEZE_PID" ]` 块（约 L204-209）内加：
   ```sh
   PID_SEEN_AT=$(date +%s)
   LISTENER_APP_OK=-1     # 可能刚升级过 app，重新探测版本
   LISTENER_FIX_FAIL=0    # 新进程给新的 3 次机会
   ```
2. **high 模式**：塞进现有的 `TICK_HIGH % 12` 分钟级复查块（约 L240），追加：
   ```sh
   if listener_ok; then
       LISTENER_FIX_FAIL=0
   else
       fix_listener
   fi
   ```
   与开关/前台服务复查同频，零额外唤醒；
3. **low 模式**：`bt_audio_active` 成立的分支里每 15 tick（≈5 分钟）执行一次同样的 `listener_ok || fix_listener`，覆盖「进程活着但监听死了」的场景（P1-3）；
4. **`listener_ok` 成功处**：重置 `LISTENER_FIX_FAIL=0`（已并入上面第 2 点的写法）。

效果：本方案同时落地 TAKEOVER 的 P0-1 与 P1-2——`listener_connected` 就是 P1-2 说的「另开的健康文件」，`files/active` 的 mtime 一行不动。

### 2.3 App 侧兜底：无 root 时的自检与告警

模块不在（无 root）时仍有解，复用两件现有资产：

- **自检特征**：`MainActivity.hasNotificationAccess()` 为真，而 `NotificationReaderService.isServiceRunning()`（`instance != null`，onDestroy 里已置空）为假 = 「授权正常但没绑定」的确切特征；
- **修复通道**：`ShizukuChannel.apply()` 已有 `cmd notification allow_listener` 能力。

建议改动（`MainActivity.java`）：

1. 把该特征从诊断报告（约 L1339）上提到首页：`updateStatus()` 命中时显示红色横幅「监听服务未绑定，播报已失效」；
2. 横幅配「修复」按钮，按顺序尝试：
   - `NotificationReaderService.requestRebindIfNeeded(context)`；
   - 3 秒后仍未恢复 → 走 Shizuku `allow_listener`；
   - 仍不行 → 引导用户到系统设置手动关闭再开启「通知使用权」。

---

## 三、其余待办处置建议

| 项 | 建议 | 成本 |
|---|---|---|
| P1-4 TTS 单点 | `initializeTextToSpeech` 重试 3 次仍失败时 `recordEvent` + 首页状态胶囊告警；已装 `com.google.android.tts` 时用 `TextToSpeech(context, listener, engine)` 显式换引擎降级 | 小 |
| P1-5 白名单手动维护 | 诊断已列白名单；在 AppPickerActivity 顶部加「最近跳过」区（`RECENT_SKIPS` 已有数据），一键加入白名单 | 小 |
| P1-1 / P2-3 版本耦合 | 本方案的版本门控即为一例；发版说明固定写「app 与模块配套版本」 | 极小 |
| P2-1 反射函数名可疑 | 留待有真机时验证后删换，不急 | — |
| P2-2 OplusHansManager | 不动，已有约束：新增拉起方式先实测 | — |
| P2-4 interruption_log 误报 | 暂保留，结合 keepalive.log 人工判断 | — |

---

## 四、验证清单（需真机 + 蓝牙耳机）

1. 重装 APK 后**不做任何手动操作**，观察 `keepalive.log` 是否在 30~90 秒内出现「已执行 disallow+allow 重绑」，且 `dumpsys notification` 的 `Live notification listeners` 重新出现本应用；
2. **版本门控回归**：装 app ≤ 2.7.8 + 模块 v1.7，确认日志出现「跳过监听自愈」，且全程没有执行过 disallow/allow（不打断正常绑定）；
3. **降级回归**：模拟修复无效（如对错误包名），确认第 3 次后停手并出现「需人工介入」日志，不再重试；
4. QQ 连发两条内容完全相同的消息，确认都播报（兼回归去重逻辑）；
5. 回归 HANDOVER.md 的实测清单：来电抢焦点、拔耳机音量回落、关总开关立即静默、磁贴与首页开关一致；
6. 首页红色横幅在「授权正常 + 监听未绑」时出现，修复后消失。

---

## 五、已知弊端与残留风险（诚实清单）

| 弊端 | 缓解 | 残留风险 |
|---|---|---|
| 修复动作（disallow+allow）会断开正常绑定，对不支持信标的旧 app 是干扰 | 版本门控 + 修复前 dumpsys 复核 | 复核后仍有极小竞争窗口（系统正在重绑时模块介入） |
| 信标只证明「系统说过绑定了」，不证明「通知真的能进来」 | 无（文件方案天花板） | 绑定成功但回调被 ROM 吞掉的故障覆盖不到 |
| 检测是低频轮询：high 60 秒、low 5 分钟 | 与现有复查同频，零额外唤醒 | 监听死亡到修复最长延迟 5 分钟（low 模式） |
| 写标记失败是静默的（异常被吞） | 修复前 dumpsys 复核兜底 + 3 次无效停手 | 假缺失最多触发 3 次无谓修复，每次间隔 5 分钟，可接受 |
| PID 复用导致陈旧标记被误认为有效 | 概率极低（需同包名新进程恰好复用旧 PID） | 接受 |
| 30 秒宽限 / 5 分钟冷却是拍的参数 | 真机验证后调整 | 需调参 |
| 只对装了 root 模块的设备生效 | app 侧横幅 + Shizuku 兜底 | 无 root 无 Shizuku 的用户仍需手动 |

---

## 六、版本建议

- app：2.7.9 / versionCode 26（新增 `listener_connected` 信标 + 首页未绑定告警）
- 模块：v1.7 / versionCode 8（新增 `listener_ok` / `listener_feature_supported` / `listener_really_missing` / `fix_listener`）

混装行为：

| app \ 模块 | ≤ v1.6 | v1.7 |
|---|---|---|
| ≤ 2.7.8 | 现状（无自愈） | 版本门控拦截，不打断正常绑定，行为同现状 |
| ≥ 2.7.9 | 信标白写但无害 | 完整自愈 |

任意单边升级都安全，但自愈能力需要两侧同时到位。
