package com.sakura.airannounce;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 监听自愈的统一入口。
 *
 * 这一层存在的理由：修复手段有四种，可靠性差一个数量级，而调用点（前台服务自检、
 * root 守护广播）不应该各自记得顺序。
 *
 *   1. 守护模块 —— 有 root 时的唯一可靠通道。应用只往私有目录写一个请求文件，
 *      以 root 运行的 airannounce-thaw 模块在 5 秒节拍里读到就执行修复。
 *      之所以不让应用自己 su：本机实测 app uid 执行 /system/bin/su 得到 ENOENT
 *      （KernelSU 只对已授予 root 的 UID 做 su 的 exec 重定向，未授权 UID 看不到该文件），
 *      「应用自己提权」在 KernelSU 上根本不成立。
 *   2. Root(su) —— Magisk / 已授权 KernelSU 设备上仍可用，作为没有装模块时的补充。
 *   3. Shizuku —— 没有 root 的设备上的降级方案。
 *   4. requestRebind —— 官方 API，但在这类 ROM 上经常是空操作，只能当最后一手。
 *
 * 顺序固定为 1 → 2 → 3，全都不行才由调用方退回 4。
 */
final class ListenerHeal {
    private static final String TAG = "AirAnnounce";

    /** 连续失败就停手，别对着无解状态每分钟刷一次 su / binder 调用。 */
    private static final int FAIL_LIMIT = 3;
    private static final long COOLDOWN_MS = 10 * 60_000L;

    private static int failStreak;
    private static volatile long blockedUntilMs;

    /**
     * 「正在切换绑定」的落盘标记。
     *
     * 切换绑定要 disallow → sleep 1 → allow，这中间有一个授权为空的窗口。
     * 执行者（守护 / su / Shizuku，也包括发出请求的应用进程）在这个窗口里被杀、
     * 被冻结、被重启，重构就会停在「权限已撤、没人补回」的状态：
     * 之后每一次自愈的前置条件都是「权限本来就在」，永远不成立，监听永久关闭，
     * 只能等人手动去系统设置里重新授权。这是整条自愈链上最危险的一处。
     *
     * 所以动手之前先把标记写进私有目录（磁盘，不是内存），补回或确认已绑定后再删。
     * 读它的有三处：{@link NotificationReaderService} 的 onListenerConnected、
     * {@link KeepAliveService} 的 60 秒自检、以及下一次 healAsync 之前的复查。
     *
     * 注意与守护模块那个标记的区别：应用侧的标记只能放自己的 files 目录，
     * 而 root 守护用的是模块目录下的同名文件（keepalive/service.sh 的 HEAL_INFLIGHT）。
     * 两者不共用同一份文件是刻意的 —— 守护往应用目录写要处理属主和 SELinux 标签，
     * 而 FBE 解锁前应用目录对守护根本不可见，偏偏开机早期的崩溃正是要防的场景。
     * 所以「应用自己的 Root/Shizuku 通道」用应用目录，「守护自己的切换」用模块目录。
     */
    static final String INFLIGHT_FILE = "heal_inflight";
    /** 正常一次切换 7 秒内结束；超过这个时间还没删标记，就是中途被打断了。 */
    private static final long INFLIGHT_SETTLE_MS = 20_000L;
    /** 再久就放弃自动补授权：可能是用户自己撤了权限，不能无限替他改回去。 */
    private static final long INFLIGHT_GIVEUP_MS = 10 * 60_000L;

    private ListenerHeal() {
    }

    /** 动手前落盘：原子写，被冻在半路也不会留下半个文件。 */
    static void markInflight(Context context) {
        File dir = context.getFilesDir();
        File target = new File(dir, INFLIGHT_FILE);
        File temp = new File(dir, INFLIGHT_FILE + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(temp);
            out.write(String.valueOf(System.currentTimeMillis()).getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            if (!temp.renameTo(target)) {
                Log.w(TAG, "heal inflight rename failed");
                temp.delete();
            }
        } catch (IOException exception) {
            Log.w(TAG, "heal inflight write failed", exception);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 已在异常路径，关闭失败没有补救手段
                }
            }
        }
    }

    /** 切回成功（或确认根本不需要切）时清掉标记。 */
    static void clearInflight(Context context) {
        try {
            new File(context.getFilesDir(), INFLIGHT_FILE).delete();
        } catch (Throwable ignored) {
            // 删不掉只意味着下一轮多做一次无害的复查
        }
    }

    /**
     * 上一轮切换是不是被打断在半路上？是的话把事情做完。
     *
     * 判据全部落在「标记年龄」和「系统当前状态」上，不依赖任何回调：
     *
     *   - 标记很新（< 20 秒）：正常流程还在跑，不动手，避免和它抢。
     *   - 权限已经在了：说明 allow 成功只是标记没删干净，清掉即可。
     *   - 权限没了且用户仍有通知使用权：这是我们自己撤的，补一次授权。
     *   - 权限没了且用户已撤使用权：不碰，直接删标记（替他改回去是越权）。
     *   - 标记超过 10 分钟：删掉，不再无限重试。
     */
    static void recoverStuck(Context context) {
        File file = new File(context.getFilesDir(), INFLIGHT_FILE);
        if (!file.isFile()) {
            return;
        }
        long age = System.currentTimeMillis() - file.lastModified();
        if (age < 0) {
            // 时钟回拨：年龄不可信，删掉标记让状态回到中性
            clearInflight(context);
            return;
        }
        if (age < INFLIGHT_SETTLE_MS) {
            return;
        }
        if (age > INFLIGHT_GIVEUP_MS) {
            clearInflight(context);
            NotificationReaderService.recordEvent("上次监听切换的标记已过期，停止自动补授权");
            Log.w(TAG, "heal inflight expired after " + (age / 1000L) + "s");
            return;
        }
        if (!MainActivity.hasNotificationAccess(context)) {
            // 用户没有通知使用权：这不是我们的窗口造成的，别替他授权
            clearInflight(context);
            return;
        }
        if (NotificationReaderService.isListenerBound()) {
            clearInflight(context);
            Log.i(TAG, "heal inflight cleared: listener is bound again.");
            return;
        }
        Log.w(TAG, "heal inflight found (age=" + (age / 1000L) + "s), re-issuing heal.");
        NotificationReaderService.recordEvent("上次监听切换被中途打断，正在补回授权");
        // healAsync 自带冷却：冷却中会返回 false，标记留着，下一个节拍再试
        healAsync(context);
    }

    /**
     * 尝试切一次监听绑定。
     *
     * @return true 表示已接管（修复在后台线程执行中，调用方不用再做别的）；
     *         false 表示没有可用通道或正在冷却，调用方应退回 requestRebind。
     */
    static boolean healAsync(final Context context) {
        if (SystemClock.elapsedRealtime() < blockedUntilMs) {
            return false;
        }
        // 用户主动撤掉通知使用权时一律不动手，别替他改回去
        if (!MainActivity.hasNotificationAccess(context)) {
            return false;
        }
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            String channel = null;
            boolean ok = false;
            try {
                // 有守护模块就交给它：这条通道不需要任何特权，也绕开了未授权 uid 看不到 su 的限制。
                // 请求是异步兑现的，投递成功即视为已接管，成败由模块日志判定。
                if (ModuleChannel.requestHeal(appContext)) {
                    failStreak = 0;
                    NotificationReaderService.recordEvent("守护模块：已投递监听修复请求");
                    return;
                }
                // 动手之前先落盘：disallow → allow 之间有授权为空的窗口，
                // 执行者在这个窗口里被杀就是「权限撤了没人补」的永久故障。
                markInflight(appContext);
                Boolean rootResult = RootChannel.healListenerSync(appContext);
                if (rootResult != null) {
                    channel = "Root";
                    ok = rootResult;
                } else {
                    Boolean shizukuResult = ShizukuChannel.healListenerSync(appContext);
                    if (shizukuResult != null) {
                        channel = "Shizuku";
                        ok = shizukuResult;
                    }
                }
            } catch (Throwable throwable) {
                Log.w(TAG, "listener heal threw", throwable);
            }
            if (channel == null) {
                NotificationReaderService.recordEvent("自检：监听未绑定，无可用提权通道");
                Log.w(TAG, "listener heal: no privileged channel available");
                return;
            }
            if (ok) {
                failStreak = 0;
                // 切换成功就把窗口标记清掉，否则守护会在 20 秒后当成「被打断」再补一次
                clearInflight(appContext);
                NotificationReaderService.recordEvent(channel + "：已切换监听绑定");
            } else {
                failStreak++;
                Log.w(TAG, channel + " listener heal failed, streak=" + failStreak);
                if (failStreak >= FAIL_LIMIT) {
                    failStreak = 0;
                    blockedUntilMs = SystemClock.elapsedRealtime() + COOLDOWN_MS;
                    NotificationReaderService.recordEvent(channel + "：监听修复连续失败，暂停 10 分钟");
                }
            }
        }, "listener-heal").start();
        return true;
    }
}
