#!/system/bin/sh
# 采样「耳机通知播报」进程的冻结状态，输出可直接贴进文档的统计。
#
# 用法（root）：
#   sh measure.sh [总秒数=600] [采样间隔=5]
#
# 为什么单独写脚本：以前的结论都是人工扒日志手算的，样本数、平均间隔、
# 冻结占比这类数字每次都要重算一遍，既慢又容易算错，也无法复现。
#
# 关键点：脚本记录的是**实际**时间戳，不是"间隔 × 次数"。息屏下 sleep 精度很差，
# 设定 5 秒可能 7~8 秒才醒一次，这个偏差本身就是需要被测量的对象。
PKG=com.sakura.airannounce
DUR=${1:-600}
IV=${2:-5}
MODDIR=/data/adb/modules/airannounce-thaw
MLOG=$MODDIR/keepalive.log
OUT=/data/local/tmp/measure-$(date +%Y%m%d-%H%M%S).log

PID=$(pidof "$PKG" 2>/dev/null | awk '{print $1}')
if [ -z "$PID" ]; then
    echo "应用没在运行（pidof 为空），先打开一次应用再采样"
    exit 1
fi

# cgroup v2 布局：/proc/<pid>/cgroup 里 0:: 后面就是相对路径
F=/sys/fs/cgroup$(sed -n 's/^0:://p' "/proc/$PID/cgroup" 2>/dev/null | head -n1)/cgroup.freeze
if [ ! -r "$F" ]; then
    F=""
    for C in /sys/fs/cgroup/apps/uid_*/pid_"$PID"/cgroup.freeze \
             /sys/fs/cgroup/uid_*/pid_"$PID"/cgroup.freeze; do
        if [ -r "$C" ]; then F=$C; break; fi
    done
fi
if [ -z "$F" ]; then
    echo "读不到冻结文件，pid=$PID"
    exit 1
fi

echo "采样开始 pid=$PID 冻结文件=$F 时长=${DUR}s 间隔=${IV}s"
echo "原始数据: $OUT"

T0=$(date +%s)
PREV=0
N=0
FROZEN=0
GAPSUM=0
GAPS=0
MAXGAP=0
MSTART=$(wc -l < "$MLOG" 2>/dev/null || echo 0)

while :; do
    NOW=$(date +%s)
    if [ "$PREV" -ne 0 ]; then
        G=$((NOW - PREV))
        GAPSUM=$((GAPSUM + G))
        GAPS=$((GAPS + 1))
        [ "$G" -gt "$MAXGAP" ] && MAXGAP=$G
    fi
    PREV=$NOW
    read -r V < "$F" 2>/dev/null
    [ -z "$V" ] && V="?"
    N=$((N + 1))
    [ "$V" = "1" ] && FROZEN=$((FROZEN + 1))
    echo "$NOW $V $PID" >> "$OUT"
    [ $((NOW - T0)) -ge "$DUR" ] && break
    sleep "$IV"
done

ELAPSED=$((PREV - T0))
[ "$GAPS" -gt 0 ] || GAPS=1
AVGGAP=$((GAPSUM / GAPS))
[ "$ELAPSED" -gt 0 ] || ELAPSED=1
FROZEN_PCT=$((FROZEN * 100 / N))

# 模块日志里这一段时间的关键事件，用来判断"当前处于哪种模式"
tail -n +$((MSTART + 1)) "$MLOG" 2>/dev/null > /tmp/_mseg.$$
HIGH=$(grep -c '进入高频解冻' /tmp/_mseg.$$ 2>/dev/null)
LOW=$(grep -c '退回低频待机' /tmp/_mseg.$$ 2>/dev/null)
STALE=$(grep -c '判定保护标记陈旧' /tmp/_mseg.$$ 2>/dev/null)
FGFAIL=$(grep -c '前台服务复查失败' /tmp/_mseg.$$ 2>/dev/null)
rm -f /tmp/_mseg.$$

echo "--- 结果 ---"
echo "样本数        : $N"
echo "实际耗时      : ${ELAPSED}s"
echo "平均采样间隔  : ${AVGGAP}s（设定 ${IV}s，差 $((AVGGAP - IV))s）"
echo "最大采样间隔  : ${MAXGAP}s"
echo "读到 frozen=1 : $FROZEN 次（${FROZEN_PCT}%）"
echo "进高频次数    : $HIGH"
echo "退回低频次数  : $LOW"
echo "判陈旧次数    : $STALE"
echo "复查失败次数  : $FGFAIL"
echo "---"
echo "原始数据已写 $OUT"
