#!/system/bin/sh
# 耳机通知播报 · 防冻结守护（root 模块，Magisk / KernelSU / APatch）
#
# ColorOS 等 ROM 会把「耳机通知播报」进程放进 cgroup freezer，冻住后收不到通知，
# 播报静默中断。本脚本常驻，只做两件事：
#   1. 应用声明需要保护时（files/active 标记），每 2 秒解冻一次；
#   2. 其余时间每 20 秒探测一次蓝牙音频，连着就解冻并拉起应用，由应用自己接管。
#
# 排查：日志写在模块目录 keepalive.log，进程号 / 冻结文件 / 解冻次数都会记录。

MODDIR=${MODDIR:-/data/adb/modules/airannounce-thaw}
PKG=com.sakura.airannounce
DATA=/data/data/$PKG
FLAG=$DATA/files/active
PREFS=$DATA/shared_prefs/airannounce_settings.xml
PIDFILE=$MODDIR/keepalive.pid
LOG=$MODDIR/keepalive.log
LOG_MAX=262144

if ! : >>"$LOG" 2>/dev/null; then
    # 模块目录不可写时退到 /data/local/tmp，adb shell 也能直接看
    LOG=/data/local/tmp/airannounce-thaw.log
    : >>"$LOG" 2>/dev/null
fi

log() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >>"$LOG" 2>/dev/null
}

rotate_log() {
    SIZE=$(wc -c "$LOG" 2>/dev/null | awk '{print $1}')
    if [ -n "$SIZE" ] && [ "$SIZE" -gt "$LOG_MAX" ]; then
        : >"$LOG"
        log "日志超过 256 KB，已清空"
    fi
}
rotate_log

# 模块更新会重新执行本脚本，先收掉老实例，避免两个循环同时解冻
if [ -r "$PIDFILE" ]; then
    OLD=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$OLD" ] && [ "$OLD" != "$$" ] && grep -qa "service.sh" "/proc/$OLD/cmdline" 2>/dev/null; then
        kill "$OLD" 2>/dev/null
        sleep 1
    fi
fi
echo $$ >"$PIDFILE"

VER=$(grep -m1 '^version=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2)
log "守护启动 pid=$$ 版本=${VER:-未知}"

# late_start 阶段执行，等系统服务就绪再开始
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 5
done
sleep 10

MODE=low
TICK_HIGH=0
TICK_IDLE=0
THAW_COUNT=0
THAW_ERR=0
FG_FAIL=0
PID=""
FREEZE_PID=""
FREEZE_FILE=""
LAST_THAW_LOG=0
LAST_WAKE=0
LAST_FAIL_LOG=0
WAKE_LOG_PID=""
ENABLED=1
RETRY=0
# 标记被判陈旧后置 1：只有 app 重新 touch（mtime 变化）才恢复高频，
# 避免残留标记让 2 秒循环整夜空转
STALE_BLOCK=0
FLAG_MTIME=0
log "系统就绪，开始守护"

app_pid() {
    set -- $(pidof "$PKG" 2>/dev/null)
    echo "${1:-}"
}

announce_enabled() {
    if [ -f "$PREFS" ] && grep -q 'name="announcements_enabled" value="false"' "$PREFS" 2>/dev/null; then
        return 1
    fi
    return 0
}

# 与 app 内 AudioManager.getDevices(GET_DEVICES_OUTPUTS) 的判断对齐：
# 已连接的蓝牙音频输出会出现在音频策略的「可用输出设备」段里。
#
# 必须只在该段内匹配，不能在整份 dump 里 grep：本机未连耳机时全文命中
# AUDIO_DEVICE_OUT_BLUETOOTH* 达 172 次，几乎全来自 "Hardware modules"
# 里的能力表（形如 {"bt_a2dp_out"; {AUDIO_DEVICE_OUT_BLUETOOTH_A2DP, @:}}，
# 注意 @: 后面是空的），那只是"这套 HAL 支持哪些设备类型"，与是否连着无关。
# 真正相连的端口长这样（@: 后面是 MAC）：
#   4. Port ID: 1265; "bt_sco_headset"; {AUDIO_DEVICE_OUT_BLUETOOTH_SCO_HEADSET, @:18:5C:...}
bt_audio_active() {
    RES=$(dumpsys media.audio_policy 2>/dev/null | awk '
        /^ Available output devices/ { seen = 1; in_out = 1; next }
        in_out && /^ [A-Za-z]/ { in_out = 0 }
        in_out && /^  [0-9]+\. Port ID:/ &&
            /AUDIO_DEVICE_OUT_(BLUETOOTH_A2DP|BLUETOOTH_SCO|BLE_HEADSET|BLE_SPEAKER)/ { bt = 1 }
        END { print (seen ? (bt ? "bt" : "none") : "nosec") }
    ')
    case "$RES" in
    bt) return 0 ;;
    nosec) ;; # 该 ROM 的 dump 没有可用设备段，回落到蓝牙管理器
    *) return 1 ;;
    esac
    dumpsys bluetooth_manager 2>/dev/null | grep -A4 -E 'A2DP:|LE_AUDIO:' | grep -qi 'ACTIVE'
}

# 冻结文件路径：先按 /proc/<pid>/cgroup 推算（cgroup v2 布局），
# 失败再按 Android 常见路径扫描一次。只在 pid 变化时调用
freeze_file_of() {
    LINE=$(sed -n 's/^0:://p' "/proc/$1/cgroup" 2>/dev/null | head -n1)
    if [ -n "$LINE" ]; then
        F="/sys/fs/cgroup$LINE/cgroup.freeze"
        if [ -f "$F" ]; then
            printf '%s\n' "$F"
            return 0
        fi
    fi
    for F in /sys/fs/cgroup/apps/uid_*/pid_"$1"/cgroup.freeze \
             /sys/fs/cgroup/uid_*/pid_"$1"/cgroup.freeze; do
        if [ -f "$F" ]; then
            printf '%s\n' "$F"
            return 0
        fi
    done
}

thaw() {
    [ -n "$FREEZE_FILE" ] && [ -r "$FREEZE_FILE" ] || return 0
    # read 是 shell 内置，不 fork；原来每 tick 用 $(cat) 会多起一个进程
    read -r FROZEN <"$FREEZE_FILE" 2>/dev/null || return 0
    [ "$FROZEN" = "1" ] || return 0
    if ! echo 0 >"$FREEZE_FILE" 2>/dev/null; then
        if [ "$THAW_ERR" = "0" ]; then
            THAW_ERR=1
            log "解冻失败：$FREEZE_FILE 不可写"
        fi
        return 0
    fi
    THAW_COUNT=$((THAW_COUNT + 1))
    # /proc/uptime 含 suspend 时间且单调，比每 tick fork 一个 date 便宜
    read -r UP _ </proc/uptime
    NOW=${UP%.*}
    if [ $((NOW - LAST_THAW_LOG)) -ge 30 ]; then
        LAST_THAW_LOG=$NOW
        log "解冻 pid=$PID（累计 $THAW_COUNT 次）"
    fi
}

# 只拉起保活前台服务：应用自己会 requestRebind 通知监听，不用再动监听授权
wake_app() {
    NOW=$(date +%s)
    [ $((NOW - LAST_WAKE)) -ge 60 ] || return 0
    LAST_WAKE=$NOW
    am start-foreground-service -n "$PKG/.KeepAliveService" >/dev/null 2>&1
    sleep 3
    NEW_PID=$(app_pid)
    if [ -n "$NEW_PID" ]; then
        if [ "$NEW_PID" != "$WAKE_LOG_PID" ]; then
            WAKE_LOG_PID=$NEW_PID
            log "拉起成功 pid=$NEW_PID"
        fi
    elif [ $((NOW - LAST_FAIL_LOG)) -ge 300 ]; then
        LAST_FAIL_LOG=$NOW
        log "拉起失败：进程未出现（检查通知权限 / 后台启动限制）"
    fi
}

foreground_ok() {
    # 进程都不在了就没必要跑一次全量 dumpsys
    [ -n "$PID" ] && [ -r "/proc/$PID/cmdline" ] || return 1
    dumpsys activity services "$PKG" 2>/dev/null | grep -q 'isForeground=true'
}

while true; do
    PID=$(app_pid)

    if [ -n "$PID" ]; then
        if [ "$PID" != "$FREEZE_PID" ]; then
            FREEZE_PID=$PID
            FREEZE_FILE=$(freeze_file_of "$PID")
            RETRY=0
            log "应用进程 pid=$PID 冻结文件=${FREEZE_FILE:-未找到}"
        fi
        # 开机早期可能还读不到 cgroup 目录，隔一阵再解析一次
        if [ -z "$FREEZE_FILE" ]; then
            RETRY=$((RETRY + 1))
            if [ "$RETRY" -ge 30 ]; then
                RETRY=0
                FREEZE_FILE=$(freeze_file_of "$PID")
                [ -n "$FREEZE_FILE" ] && log "重新解析到冻结文件：$FREEZE_FILE"
            fi
        fi

        # 标记被判陈旧后，只有 app 重新 touch（mtime 变化）才恢复高频
        if [ "$STALE_BLOCK" = "1" ]; then
            CUR_MTIME=$(stat -c %Y "$FLAG" 2>/dev/null)
            if [ -n "$CUR_MTIME" ] && [ "$CUR_MTIME" != "$FLAG_MTIME" ]; then
                STALE_BLOCK=0
                FLAG_MTIME=$CUR_MTIME
                log "保护标记已刷新，恢复高频保护"
            fi
        fi

        if [ -f "$FLAG" ] && [ "$STALE_BLOCK" != "1" ]; then
            if [ "$MODE" != "high" ]; then
                MODE=high
                TICK_HIGH=0
                FG_FAIL=0
                log "应用要求保护，进入高频解冻（5 秒一次）"
            fi
            thaw
            TICK_HIGH=$((TICK_HIGH + 1))
            # 一分钟做一次状态复查：开关、前台服务是否还在
            if [ $((TICK_HIGH % 12)) -eq 0 ]; then
                if ! announce_enabled; then
                    # 标记归 app 所有，模块只读不删：删掉后 app 若还活着就不会重建，
                    # 会退化成永久不保护。这里只降级，等 app 下次拉起刷新 mtime
                    MODE=low
                    ENABLED=0
                    STALE_BLOCK=1
                    FLAG_MTIME=$(stat -c %Y "$FLAG" 2>/dev/null)
                    log "播报开关已关闭，退回低频待机（保留标记，等应用刷新）"
                    sleep 20
                    continue
                fi
                if foreground_ok; then
                    if [ "$FG_FAIL" != "0" ]; then
                        FG_FAIL=0
                        log "前台服务复查恢复正常"
                    fi
                else
                    FG_FAIL=$((FG_FAIL + 1))
                    if [ "$FG_FAIL" -ge 3 ]; then
                        # 连续 3 次复查失败（复查每分钟一次，约 3 分钟）：标记多半是应用被杀后留下的，
                        # 继续高频空转会让用户直观感受到"这模块费电"
                        STALE_BLOCK=1
                        FLAG_MTIME=$(stat -c %Y "$FLAG" 2>/dev/null)
                        MODE=low
                        TICK_HIGH=0
                        FG_FAIL=0
                        log "前台服务连续 3 次复查失败，判定保护标记陈旧：暂停高频解冻"
                        sleep 20
                        continue
                    fi
                    log "前台服务复查失败（$FG_FAIL/3）"
                fi
            fi
            if [ $((TICK_HIGH % 120)) -eq 0 ]; then
                log "心跳 模式=high pid=$PID 累计解冻=$THAW_COUNT"
                rotate_log
            fi
            # 5 秒而非 2 秒：实测息屏下 sleep 精度很差，2 秒节奏平均 7.7 秒才醒一次，
            # 相当于用"每 2 秒一次唤醒"的代价买到"7 秒一次的实际解冻"，纯亏
            sleep 5
            continue
        fi
    elif [ -n "$FREEZE_PID" ]; then
        log "应用进程已不在（旧 pid=$FREEZE_PID）"
        FREEZE_PID=""
        FREEZE_FILE=""
    fi

    if [ "$MODE" != "low" ]; then
        MODE=low
        log "退回低频待机（20 秒一次）"
    fi

    if announce_enabled; then
        ENABLED=1
        if bt_audio_active; then
            thaw
            wake_app
        fi
    elif [ "$ENABLED" != "0" ]; then
        ENABLED=0
        log "播报开关已关闭，停止主动拉起"
    fi

    TICK_IDLE=$((TICK_IDLE + 1))
    if [ "$TICK_IDLE" -ge 30 ]; then
        TICK_IDLE=0
        log "心跳 模式=low pid=${PID:-无} 累计解冻=$THAW_COUNT 开关=$ENABLED"
        rotate_log
    fi
    sleep 20
done
