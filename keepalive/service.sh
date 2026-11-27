#!/system/bin/sh
# 耳机通知播报 · 防冻结守护（root 模块，Magisk / KernelSU / APatch）
#
# ColorOS 等 ROM 会把「耳机通知播报」进程放进 cgroup freezer，冻住后收不到通知，
# 播报静默中断。本脚本常驻，只做两件事：
#   1. 应用声明需要保护时（files/active 标记），每 5 秒解冻一次；
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
# 应用 → 模块的监听修复请求（应用无权 su，只能反向投递给守护执行）
HEAL_REQUEST=$DATA/files/heal_request
# 模块 → 应用的存活心跳（应用读不到 /data/adb，只能看这个文件的新鲜度）
MODULE_SEEN=$DATA/files/module_seen
# 守护自己的「切换中」标记。为什么不放 $DATA/files：
#   1) 应用目录在 FBE 解锁前不可见，而开机早期的崩溃正是这个标记要防的场景；
#   2) 守护往应用目录写文件要处理属主和 SELinux 标签，模块目录没有这些问题。
HEAL_INFLIGHT=$MODDIR/heal_inflight
# 播报音量兜底：应用 boost 前落盘 original target max leaseUntil kind，
# 进程被冻住/杀掉时由这里按租约还原（app 内定时器冻住就停摆了）。
VOLUME_MARKER=$DATA/files/volume_original
# 监听组件名：fix_listener 与补授权块共用
LC="$PKG/com.sakura.airannounce.NotificationReaderService"
# 最近一次请求的处理结果，作为回执写进 module_seen
LAST_RESULT=""
# 上次因为「音量标记过期」拉起应用的时刻：30 秒内不重复拉起
VOL_WAKE_AT=0
# 上次执行 disallow/allow 切换的时刻：30 秒内不重切
LAST_FIX_AT=0
# 默认 TTS 引擎。它被冻住时 speak() 会返回成功却永远等不到 onStart，
# 应用侧只能记成失败并推高重建计数 —— 所以这里顺手把它也解冻。
TTS_PKG=$(settings get secure tts_default_synth 2>/dev/null)
case "$TTS_PKG" in
'' | null | */*) TTS_PKG="" ;;
esac
VER=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null)
LAST_HEAL_REQ=""
SEEN_AT=0

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

# 有些 root shell（例如某些工具 App 的终端）跑在隔离的挂载命名空间里，
# 看不到其他应用的私有目录。在这种环境里启动本脚本，$FLAG 永远不存在，
# 于是永远停在低频模式、日志也不报错——"守护在跑但从不保护"，最难排查的那种。
# v1.12 起自己用 nsenter 重入 init 命名空间再执行一次，AIRANNOUNCE_NS 防递归；
# exec 保留 PID，单实例清理逻辑不受影响。
if [ ! -d "$DATA" ] && [ -z "${AIRANNOUNCE_NS:-}" ] && command -v nsenter >/dev/null 2>&1; then
    SELF=$MODDIR/service.sh
    [ -f "$SELF" ] || SELF=$0
    log "检测到隔离挂载命名空间，nsenter 重入 init 命名空间：$SELF"
    export AIRANNOUNCE_NS=1
    exec nsenter -t 1 -m -- "$SELF" "$@"
fi

if [ ! -d "$DATA" ]; then
    log "警告：看不到 $DATA，本实例很可能在隔离挂载命名空间里，将无法进入高频模式。请在开机时由模块自行启动，或用 nsenter -t 1 -m 启动"
fi

# late_start 阶段执行，等系统服务就绪再开始
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 5
done
sleep 10

MODE=low
TICK_HIGH=0
LISTENER_FAIL=0
LISTENER_SKIP=0
TICK_IDLE=0
TICK_BT=0
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
# 标记被判陈旧后置 1：只有 app 重新写入新鲜时间戳才恢复高频，
# 避免残留标记让 5 秒循环整夜空转。v1.12 起除了等 app 刷新，还会周期性复核
# 前台服务是否真的在线（见主循环里的「陈旧复核」），防止标记残留把模块永久锁死在 low。
STALE_BLOCK=0
FLAG_MTIME=0
STALE_RECHECK=0
STALE_OK=0
log "系统就绪，开始守护"

app_pid() {
    set -- $(pidof "$PKG" 2>/dev/null)
    echo "${1:-}"
}

# 保护标记的年龄（秒）。优先读文件内容里的毫秒时间戳（app 2.8.6+ 写入），
# 读不到再回退 mtime。旧版 app 写的是空文件，这个回退分支必须保留，
# 否则「新模块 + 旧 app」会把所有标记判成陈旧，反而制造永久广播循环。
# 传入 NOW 可复用调用方已经算过的时间，省掉一次 fork（low 模式每 tick 都会问）。
flag_age() {
    NOW=${1:-$(date +%s)}
    TS=$(cat "$FLAG" 2>/dev/null)
    # 2.9.4+ 写的是「<毫秒戳> <0|1>」，只取第一段做时间戳；
    # 旧版 app 写的是纯毫秒戳，${TS%% *} 对它是恒等操作，向后兼容。
    TS=${TS%% *}
    case "$TS" in
        ''|*[!0-9]*)
            MT=$(stat -c %Y "$FLAG" 2>/dev/null)
            if [ -n "$MT" ]; then
                echo $(( NOW - MT ))
            else
                echo 999999
            fi
            ;;
        *)
            # 13 位毫秒戳不要送进 $(( ))：busybox ash 的算术是 32 位有符号，
            # 1795435782000 会溢出成 140652272，标记立刻被判成"4 年前"。
            # 用字符串截掉后三位取秒，纯 POSIX 参数扩展，无溢出。
            [ ${#TS} -ge 13 ] && TS=${TS%???}
            echo $(( NOW - TS ))
            ;;
    esac
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

# ---------------- 监听绑定自愈 ----------------
# 这台 ROM 上 NotificationListenerService.requestRebind() 经常是空操作：
# app 确实发了重绑请求，系统不理，表现为"进程在、前台服务在、耳机连着、
# 通知使用权也还在，但一条都不播"。2026-11-11 复现 3 次 requestRebind 全失败，
# 而 disallow -> allow 切一次 3/3 全部成功。
#
# 注意：自动调用 allow_listener 等于替用户授权，所以必须先确认权限本来就在；
# 用户主动撤权时绝不能替他改回去。
listener_granted() {
    settings get secure enabled_notification_listeners 2>/dev/null \
        | tr ':' '\n' | grep -q "com.sakura.airannounce"
}

# 绑定位不再单独开文件，改为读 active 内容里的第二段（2.9.4+ 写入 <毫秒戳> <0|1>）。
# 原因（本机实测复现过）：只在事件里增删的独立标记文件，进程被 SIGKILL 时不会被删，
# 模块会拿残留标记一直认为"已绑定"，从而永久跳过 dumpsys 复查与自愈。
# active 由保活服务每 60 秒重写，进程一死时间戳立刻变旧，陈旧度天然覆盖残留问题。
# 本机实测全量 dumpsys notification 单次 347～364 ms、输出 1.5 MB，
# 每分钟一次折合约 0.85% 单核，比应用自己的轮询还贵，所以快路径必须便宜。
# 限频计数：4 次跳过 ≈ 5 分钟一次（高频循环 5 秒一跳）
LISTENER_DUMP_SKIP=0

# 立刻跑一次真实检查，不受限频影响。fix_listener 重绑后的复查必须用它：
# 限频逻辑在没有证据时会「按已绑定处理」，如果复查走限频路径，
# 会把重绑失败直接判成成功，还会把失败计数清零，掩盖真实故障。
listener_bound_now() {
    dumpsys notification 2>/dev/null \
        | sed -n '/Live notification listeners/,/Snoozed/p' \
        | grep -q "com.sakura.airannounce"
}

# 从 active 内容读绑定位：1=已绑定，0=未绑定，空=旧版 app 未提供该字段。
listener_flag() {
    set -- $(cat "$FLAG" 2>/dev/null)
    echo "${2:-}"
}

listener_bound() {
    # 快路径：标记新鲜（保活服务 60 秒重写一次，这里留 3 分钟余量）且明确写着已绑定。
    # 只用"明确的 1"当证据；0、缺字段、标记陈旧三种情况一律回落真实复查。
    if [ "$(listener_flag)" = "1" ] && [ "$(flag_age)" -lt 180 ]; then
        return 0
    fi
    # 限频只作用于这条周期检查路径；fix_listener 的即时复查走 listener_bound_now，
    # 不受这里影响——否则会把重绑失败误判成成功。
    if [ "$LISTENER_DUMP_SKIP" -gt 0 ]; then
        LISTENER_DUMP_SKIP=$((LISTENER_DUMP_SKIP - 1))
        return 0
    fi
    LISTENER_DUMP_SKIP=4
    listener_bound_now
}

fix_listener() {
    # 节流：切换本身要 7 秒左右，期间绑定状态本来就不稳。上一次失败后
    # 立刻再来一次，会在两轮之间反复 disallow，越切越乱（本机实测连切 3 次）。
    if [ $((NOW_S - LAST_FIX_AT)) -lt 30 ]; then
        log "距上次切换不足 30 秒（$((NOW_S - LAST_FIX_AT)) 秒），本轮跳过"
        return 1
    fi
    LAST_FIX_AT=$NOW_S
    log "监听未绑定（权限仍在），执行 disallow/allow 切换"
    # 先把「切换中」标记落盘再动手：disallow 之后到 allow 生效之间有一段授权为空
    # 的窗口，本进程若在窗口里被杀/重启，权限就永远停在空的状态 —— 之后每次自愈的
    # 前置条件都是「权限本来就在」，永远不成立，监听就此永久关闭，只能人工重新授权。
    date +%s > "$HEAL_INFLIGHT" 2>/dev/null
    cmd notification disallow_listener "$LC" 0 >/dev/null 2>&1
    sleep 1
    cmd notification allow_listener "$LC" 0 >/dev/null 2>&1
    sleep 3
    if ! listener_bound_now; then
        # 2026-11-12 现场：切换本身没问题，但进程刚被解冻/刚重启时，绑定要几秒才生效，
        # 只在 2 秒后就判定失败会误判。这里先叫醒进程，再给一次机会。
        wake_app
        sleep 3
    fi
    # 成功判定要回读设置而不是信退出码：cmd 在多数失败情形下也返回 0，
    # 只有 enabled_notification_listeners 里真的还有本组件才算授权回来了。
    if listener_bound_now && listener_granted; then
        log "监听已重新绑定"
        rm -f "$HEAL_INFLIGHT"
        LISTENER_FAIL=0
        LISTENER_SKIP=0
        LAST_RESULT="fixed"
    else
        # 标记故意保留：节拍会看见它并补一次，直到授权回来或超过 10 分钟
        LISTENER_FAIL=$((LISTENER_FAIL + 1))
        log "监听重绑失败（$LISTENER_FAIL/3）"
        if [ "$LISTENER_FAIL" -ge 3 ]; then
            # 以前这里直接停手，且计数不会自己归零——一旦达到 3 次就再也不修，
            # 2026-11-12 因此出现了 23 分钟的哑火。改成冷却 10 分钟后重来。
            LISTENER_FAIL=0
            LISTENER_SKIP=120
            log "监听修复连续失败，10 分钟内不再尝试"
        fi
    fi
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

# 拉起应用进程。
# 用显式广播打到应用里的 WakeReceiver：进程被拉起后在本进程内启动前台服务，
# 不弹界面、不点亮屏幕，亮屏息屏都能用。
# 不能用 am start*-service：KeepAliveService 是 exported="false"，跨 uid 启动会被
# 拒（实测 ColorOS 的 OplusHansManager 返回 "Not found"）。
# 也不再用 am start-activity：会切前台并点亮屏幕，打扰用户（v1.5 的做法）。
wake_app() {
    # 第三种死锁（v1.10 已修）：进程活着但前台服务没起来（重启后常见），没有
    #   files/active → 模块停在 low；又不广播 → 没人叫 syncKeepAlive → 服务永远
    #   起不来。
    # 第四种死锁（v1.12 修）：重启前留下的旧标记还在，进程也被系统按通知监听绑定
    #   起来了，但前台服务从未启动。旧逻辑「标记在就 return」把这种状态当成
    #   "已受保护"，于是永不广播；而标记又只有服务启动时才会刷新 → 模块的
    #   STALE_BLOCK 永久锁死。2026-11-22 22:46 起本机就这样空转了 21 小时。
    # 现在只有「进程在 + 标记新鲜（<120s）+ 未判陈旧」才短路；标记陈旧按 60 秒
    # 勤拉，标记缺失（应用不要求保护）仍按 5 分钟。
    NOW=$(date +%s)
    LIMIT=60
    if [ -n "$(app_pid)" ]; then
        if [ -f "$FLAG" ] && [ "$STALE_BLOCK" != "1" ] && [ "$(flag_age "$NOW")" -lt 120 ]; then
            return 0
        fi
        [ -f "$FLAG" ] || LIMIT=300
    fi
    [ $((NOW - LAST_WAKE)) -ge $LIMIT ] || return 0
    LAST_WAKE=$NOW
    WAKE_OUT=$(am broadcast --user 0 --include-stopped-packages \
        -a "$PKG.WAKE" -n "$PKG/.WakeReceiver" 2>&1)
    sleep 3
    NEW_PID=$(app_pid)
    if [ -z "$NEW_PID" ]; then
        if [ $((NOW - LAST_FAIL_LOG)) -ge 300 ]; then
            LAST_FAIL_LOG=$NOW
            log "拉起失败：进程未出现（检查应用是否为 2.7.6+）am=$(printf '%s' "$WAKE_OUT" | head -n1)"
        fi
        return 0
    fi
    if [ "$NEW_PID" != "$WAKE_LOG_PID" ]; then
        WAKE_LOG_PID=$NEW_PID
        log "拉起成功 pid=$NEW_PID"
    fi
}

foreground_ok() {
    # 进程都不在了就没必要跑一次全量 dumpsys
    [ -n "$PID" ] && [ -r "/proc/$PID/cmdline" ] || return 1
    dumpsys activity services "$PKG" 2>/dev/null | grep -q 'isForeground=true'
}

while true; do
    # 时间戳每个节拍取一次就够，后面几块都要用；原来只在心跳里取，
    # 补救块要用时还得再 fork 一次 date。
    NOW_S=$(date +%s)

    # ---- 应用请求的监听修复 ----
    # 为什么是文件而不是命令：本机实测 app uid 执行 /system/bin/su 得到 ENOENT
    # （KernelSU 只对已授权 UID 做 su 的 exec 重定向，未授权 UID 看不到该文件），
    # 「应用自己提权」在 KernelSU 上不成立。应用写自己的 files 目录不需要任何权限，
    # 守护读到就代它执行 —— 这是有 root 时唯一稳的一条通道。
    if [ -f "$HEAL_REQUEST" ]; then
        CUR_REQ=$(cat "$HEAL_REQUEST" 2>/dev/null)
        # 只认内容变化：应用会保留该文件，靠时间戳区分新请求，避免写删竞争
        if [ -n "$CUR_REQ" ] && [ "$CUR_REQ" != "$LAST_HEAL_REQ" ]; then
            LAST_HEAL_REQ=$CUR_REQ
            log "收到应用监听修复请求（$CUR_REQ）"
            if listener_granted && ! listener_bound_now; then
                fix_listener
            else
                log "复查后监听仍正常，忽略该请求"
                LAST_RESULT="ignored"
            fi
        fi
    fi

    # ---- 上次切换绑定被打断的补救 ----
    # 这是整条自愈链上最危险的一处：切换窗口里守护被杀/重启，授权就停在空的状态，
    # 而所有自愈路径的前置条件都是「权限本来就在」，于是永久不成立。
    if [ -f "$HEAL_INFLIGHT" ]; then
        IF_TS=$(cat "$HEAL_INFLIGHT" 2>/dev/null)
        case "$IF_TS" in
        '' | *[!0-9]*) IF_TS=$NOW_S ;;
        esac
        IF_AGE=$((NOW_S - IF_TS))
        if [ "$IF_AGE" -ge 600 ]; then
            # 十分钟还没补回来，多半是用户自己撤了授权：不再替他改
            rm -f "$HEAL_INFLIGHT"
            log "切换标记超过 10 分钟，放弃自动补授权"
        elif [ "$IF_AGE" -ge 5 ]; then
            if listener_granted; then
                if listener_bound_now; then
                    rm -f "$HEAL_INFLIGHT"
                    log "上次的监听切换已完成，清理标记"
                elif [ "$IF_AGE" -ge 20 ] && [ $((NOW_S - LAST_FIX_AT)) -ge 30 ]; then
                    # 只在真正要动手时打日志：否则连续失败期间每个节拍都会
                    # 刷一行「重新切一次」再被节流跳过，日志会被这点噪音淹掉
                    log "上次的监听切换被打断（$IF_AGE 秒），重新切一次"
                    fix_listener
                fi
            else
                # 授权已经空了：这就是要救的状态，立刻补，不等 20 秒
                log "上次切换把授权撤掉了（$IF_AGE 秒），立即补 allow_listener"
                cmd notification allow_listener "$LC" 0 >/dev/null 2>&1
                rm -f "$HEAL_INFLIGHT"
                sleep 2
                if listener_bound_now; then
                    log "监听已补回"
                    LAST_RESULT="recovered"
                else
                    log "补回失败，下一拍重试"
                fi
            fi
        fi
    fi

    # ---- 播报音量兜底 ----
    # 应用 boost 前会落盘 original target max leaseUntil kind。进程被冻进 freezer 时
    # 它自己的定时器跟着停摆，音量就停在抬高值上一直冲耳朵，只能靠这里收尾。
    if [ -f "$VOLUME_MARKER" ]; then
        read -r V_ORIG V_TARGET V_MAX V_LEASE V_KIND < "$VOLUME_MARKER" 2>/dev/null
        case "$V_LEASE" in
        '' | *[!0-9]*) V_LEASE=0 ;;
        esac
        CUR_MS=$((NOW_S * 1000))
        if [ -n "$V_ORIG" ] && [ "$CUR_MS" -ge $((V_LEASE + 5000)) ]; then
            if [ "$V_KIND" = "bt" ] && ! bt_audio_active; then
                # 原值是耳机那一档。耳机不在时写进去会改扬声器那一档，
                # 而耳机那一档还留在 boost 值上 —— 等设备回来再处理。
                :
            elif [ -z "$(app_pid)" ]; then
                # 这里刻意**不**用 cmd media_session volume 直接改音量。
                # 本机实测该命令会 clamp（set 26 得到 16、set 60 得到 50、
                # set 140 得到 100），拿它「还原」等于随机改用户音量，
                # 戴耳机时就是往耳朵里怼。正确做法只有一条：拉起应用，
                # 让应用用 AudioManager 精确还原（应用侧恢复是精确的）。
                if [ $((NOW_S - VOL_WAKE_AT)) -ge 30 ]; then
                    VOL_WAKE_AT=$NOW_S
                    log "音量标记已过期，拉起应用让它自己还原（不碰音量）"
                    wake_app
                fi
            fi
        fi
    fi

    # ---- 存活心跳 ----
    # 每分钟刷一次 mtime，应用据此判断「有守护可用」，从而决定走模块还是 Shizuku。
    # 内容扩成「时间戳 版本 最后请求 结果」，同时当心跳和请求回执：
    # 只靠 onListenerConnected 当回执有洞 —— 「复查后忽略」不会产生那个回调，
    # 应用只看到沉默，分不清「模块没在」和「监听本来就好」。
    if [ $((NOW_S - SEEN_AT)) -ge 60 ] || [ ! -f "$MODULE_SEEN" ]; then
        SEEN_AT=$NOW_S
        if echo "$NOW_S $VER $LAST_HEAL_REQ $LAST_RESULT" > "$MODULE_SEEN" 2>/dev/null; then
            # 必须交还给应用 uid：root 属主的 600 文件 untrusted_app 读不到，心跳就白写了
            # 属主连组一起交还：只改 user 时组仍是 root，虽然 600 下不影响读取，
            # 但后续若改成 640/660 就会立刻变成「应用读不到」的隐性故障
            chown "$(stat -c %u:%g "$DATA" 2>/dev/null)" "$MODULE_SEEN" 2>/dev/null
            chmod 600 "$MODULE_SEEN" 2>/dev/null
            # 属主改回应用之后标签也得跟着改，否则 untrusted_app 仍然 EACCES，
            # 结果就是应用永远判「模块不在」，无条件降级到 Shizuku。
            restorecon "$MODULE_SEEN" >/dev/null 2>&1
        fi
    fi
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
            # 每 5 拍（约 25 秒）顺手解冻一次 TTS 引擎：只解冻不拉起，
            # 引擎由系统按需 bind。放高频块里是因为它只在「有耳机要播报」时才有意义。
            if [ -n "$TTS_PKG" ] && [ $((TICK_HIGH % 5)) -eq 0 ]; then
                TTS_PID=$(pidof "$TTS_PKG" 2>/dev/null | awk '{print $1}')
                if [ -n "$TTS_PID" ]; then
                    TTS_FREEZE_F=$(freeze_file_of "$TTS_PID")
                    if [ -n "$TTS_FREEZE_F" ] && [ -r "$TTS_FREEZE_F" ]; then
                        read -r TTS_FROZEN <"$TTS_FREEZE_F" 2>/dev/null
                        if [ "$TTS_FROZEN" = "1" ] \
                            && echo 0 >"$TTS_FREEZE_F" 2>/dev/null; then
                            log "顺带解冻 TTS 引擎 $TTS_PKG pid=$TTS_PID"
                        fi
                    fi
                fi
            fi
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
            # 每分钟一次监听健康检查：权限在、服务却没绑上——模块以前完全看不见这一态
            if [ "$LISTENER_SKIP" -gt 0 ]; then
                LISTENER_SKIP=$((LISTENER_SKIP - 1))
            elif [ $((TICK_HIGH % 12)) -eq 0 ] || [ "$TICK_HIGH" -eq 1 ]; then
                if listener_granted && ! listener_bound; then
                    fix_listener
                fi
            fi
            if [ $((TICK_HIGH % 120)) -eq 0 ]; then
                log "心跳 模式=high pid=$PID 累计解冻=$THAW_COUNT"
                rotate_log
            fi
            # 5 秒而非 2 秒：四组实测 sleep 精度正常（设定 5 秒实测 5.0 秒、设定 2 秒实测 2.0 秒），
            # 改成 5 秒是把唤醒次数降到 1/2.5，而 2 秒能买到的解冻速度优势测不出来，取更省电的一侧
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
            # 低频模式也要查监听：这里曾经是个死锁——
            #   监听没绑 → NotificationReaderService 不创建 → app 不起前台服务
            #   → 没有 files/active → 模块停在 low → 而监听修复只写在 high 分支里
            #   → 永远没人修，直到用户手动去通知使用权里关一次再开一次。
            # 每 3 个 tick（约 60 秒）查一次，和 high 模式共用同一套计数与冷却。
            if [ "$LISTENER_SKIP" -gt 0 ]; then
                LISTENER_SKIP=$((LISTENER_SKIP - 1))
            elif [ $((TICK_IDLE % 3)) -eq 0 ]; then
                if listener_granted && ! listener_bound; then
                    fix_listener
                fi
            fi
            # 耳机判定成功但没标记时，以前完全静默：日志只有"模式=low"，
            # 分不清"没认出耳机"和"认出了但没有要拉起的对象"，排查时会误判成前者。
            TICK_BT=$((TICK_BT + 1))
            if [ $((TICK_BT % 30)) -eq 0 ]; then
                if [ -f "$FLAG" ]; then
                    log "待机中检测到蓝牙音频输出，已尝试拉起（保护标记在）"
                else
                    log "待机中检测到蓝牙音频输出，已尝试拉起（无保护标记：应用未要求保护）"
                fi
            fi
        else
            TICK_BT=0
        fi
    elif [ "$ENABLED" != "0" ]; then
        ENABLED=0
        log "播报开关已关闭，停止主动拉起"
    fi

    # 陈旧复核：只等 app 刷新标记会被「服务已起、标记没刷新」卡死。每 5 分钟
    # （约 15 个 tick）看一次前台服务，连续 2 次确认在线才解除 STALE_BLOCK，
    # 单次抖动不解锁，避免 high/low 反复横跳。
    if [ "$STALE_BLOCK" = "1" ]; then
        STALE_RECHECK=$((STALE_RECHECK + 1))
        if [ "$STALE_RECHECK" -ge 15 ]; then
            STALE_RECHECK=0
            if foreground_ok; then
                STALE_OK=$((STALE_OK + 1))
                log "陈旧复核：前台服务在线（$STALE_OK/2）"
                if [ "$STALE_OK" -ge 2 ]; then
                    STALE_BLOCK=0
                    STALE_OK=0
                    TICK_HIGH=0
                    FG_FAIL=0
                    FLAG_MTIME=$(stat -c %Y "$FLAG" 2>/dev/null)
                    log "服务活性复核通过，解除陈旧标记，恢复高频保护"
                fi
            elif [ "$STALE_OK" != "0" ]; then
                STALE_OK=0
                log "陈旧复核：前台服务又不在线，计数归零"
            fi
        fi
    fi

    TICK_IDLE=$((TICK_IDLE + 1))
    if [ "$TICK_IDLE" -ge 30 ]; then
        TICK_IDLE=0
        log "心跳 模式=low pid=${PID:-无} 累计解冻=$THAW_COUNT 开关=$ENABLED"
        rotate_log
    fi
    sleep 20
done
