package com.sakura.airannounce;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Root 通道。
 *
 * 与 Shizuku 的分工必须清楚：Shizuku 是给「没有 root」的设备准备的降级方案，
 * 要靠用户手动装 App、开无线调试、配对，重启后还得重新激活；
 * 本机有 root（Magisk / KernelSU / APatch）时，一条 su 就够，没必要再绕一层。
 *
 * 之前自愈链只挂了 Shizuku 一条腿，Shizuku 进程一停（重启后没重新激活是常态），
 * 「权限还在、服务没绑」这个状态就彻底无人处理，只能等人手动开一下 App。
 * 所以这里补上 root 腿，并且在 {@link ListenerHeal} 里把顺序固定成 root → Shizuku。
 */
final class RootChannel {
    private static final String TAG = "AirAnnounce";

    /**
     * 探测成功后的缓存时长：listenerCheck 每分钟一次，别每次都 fork 一个 su 进程。
     */
    private static final long PROBE_OK_TTL_MS = 60_000L;
    /**
     * 探测失败的缓存时长要比成功长得多。
     *
     * 首次调用会弹出 Root 授权框，用户点「允许」需要时间，这次探测几乎一定超时失败；
     * 如果失败也只缓存 60 秒，用户刚点完允许、下一分钟又弹一次，体验很糟。
     * 反过来缓存太久又会让「刚授权完却不生效」。5 分钟是两边的折中。
     */
    private static final long PROBE_FAIL_TTL_MS = 5 * 60_000L;
    /**
     * 探测本身的上限。授权弹窗挂在屏幕上没人点时，读取会一直阻塞，必须设界；
     * 但给太短会让「用户在弹窗上点允许」这件事永远抢不过超时，所以留 5 秒。
     */
    private static final long PROBE_TIMEOUT_MS = 5_000L;
    /** 真正执行修复命令的上限：disallow 之后要 sleep 1 再 allow。 */
    private static final long COMMAND_TIMEOUT_MS = 15_000L;

    /**
     * su 的候选路径。不能只写 "su" 交给 PATH：
     * 实测本机 App 进程里 ProcessBuilder("su") 直接抛 IOException，
     * 而 root shell 下 /system/bin/su 一切正常 —— 说明问题在执行路径而非授权。
     * 按绝对路径逐个试，找到第一个存在且可执行的。
     */
    private static final String[] SU_CANDIDATES = {
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
    };

    /** 探测到的 su 路径；null 表示还没找到。 */
    private static volatile String suBinary;

    private static volatile boolean probeDone;
    private static volatile boolean probeOk;
    private static volatile long probeAtMs;

    private RootChannel() {
    }

    /**
     * 是否真的拿到了 uid 0。
     *
     * 判据只能是「执行 id -u 并读回 0」：su 二进制存在、进程能 fork 都不代表有授权，
     * Magisk 在未授权的调用上会直接让命令失败。
     */
    /** 找到第一个可执行的 su；找不到返回 null。 */
    private static String resolveSu() {
        String cached = suBinary;
        if (cached != null) {
            return cached;
        }
        for (String candidate : SU_CANDIDATES) {
            try {
                java.io.File file = new java.io.File(candidate);
                if (file.isFile() && file.canExecute()) {
                    suBinary = candidate;
                    Log.i(TAG, "su binary found: " + candidate);
                    return candidate;
                }
            } catch (Throwable ignored) {
                // 单个候选检查失败不影响继续试下一个
            }
        }
        Log.i(TAG, "no executable su found in candidates");
        return null;
    }

    static boolean isAvailable(Context context) {
        long now = SystemClock.elapsedRealtime();
        long ttl = probeOk ? PROBE_OK_TTL_MS : PROBE_FAIL_TTL_MS;
        if (probeDone && now - probeAtMs < ttl) {
            return probeOk;
        }
        boolean ok = false;
        String su = resolveSu();
        if (su == null) {
            probeAtMs = SystemClock.elapsedRealtime();
            probeDone = true;
            probeOk = false;
            return false;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(su, "-c", "id -u")
                    .redirectErrorStream(true)
                    .start();
            String output = drain(process.getInputStream());
            if (process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    && process.exitValue() == 0) {
                ok = "0".equals(output.trim());
            }
        } catch (Throwable throwable) {
            // 打 message 而不只是类名：IOException 的 message 会明确写出
            // 「No such file or directory」还是「Permission denied(13)」，
            // 这两种情况的处理方式完全不同。
            Log.i(TAG, "root probe failed: " + throwable.getClass().getSimpleName()
                    + " (" + throwable.getMessage() + ")");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        probeOk = ok;
        probeAtMs = now;
        probeDone = true;
        Log.i(TAG, "root available=" + ok);
        return ok;
    }

    /** 上一次探测的结论，不触发新的 su 调用。 */
    static boolean lastKnownAvailability() {
        return probeDone && probeOk;
    }

    /**
     * 用 root 切一次监听绑定。
     *
     * 为什么不直接 requestRebind：这台 ROM 上它基本是空操作（2026-11-11 实测 3/3 失败），
     * 而 `disallow_listener` → 等 1 秒 → `allow_listener` 切换 3/3 成功。
     *
     * @return null 表示该通道不可用（调用方应继续试下一条），true/false 表示可用但成败
     */
    static Boolean healListenerSync(Context context) {
        if (!isAvailable(context)) {
            return null;
        }
        String listener = context.getPackageName() + "/"
                + NotificationReaderService.class.getName();
        StringBuilder report = new StringBuilder();
        boolean ok = runSu("cmd notification disallow_listener " + listener
                + "; sleep 1; cmd notification allow_listener " + listener, report);
        Log.i(TAG, "root heal result=" + ok + " " + report.toString().trim());
        return ok;
    }

    /**
     * 用 root 补一遍 system 侧设置，返回给用户看的执行报告。
     * 与 Shizuku 版的 apply 等价，只是不需要额外装东西。内部会 fork，需在后台线程调用。
     */
    static String apply(Context context) {
        StringBuilder report = new StringBuilder();
        if (!isAvailable(context)) {
            return "本机没有可用的 Root 授权";
        }
        String packageName = context.getPackageName();
        String listener = packageName + "/" + NotificationReaderService.class.getName();
        runSu("cmd deviceidle whitelist +" + packageName, report);
        runSu("am set-standby-bucket " + packageName + " active", report);
        runSu("cmd notification allow_listener " + listener, report);
        return report.toString().trim();
    }

    static String describe(Context context) {
        if (!probeDone) {
            return "未检测";
        }
        return probeOk ? "已取得 Root（uid 0）" : "无 Root 授权";
    }

    /**
     * 执行一条 su 命令。
     * stdout / stderr 合并后一次读完再 waitFor：分开顺序读时只要有一边先填满管道，
     * 子进程就会卡在写那一边，而我们在等另一边读完，waitFor 永远挂起。
     */
    private static boolean runSu(String command, StringBuilder report) {
        String su = resolveSu();
        if (su == null) {
            report.append('\u2717').append(' ').append(command).append("（找不到 su）\n");
            return false;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(su, "-c", command)
                    .redirectErrorStream(true)
                    .start();
            String output = drain(process.getInputStream());
            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                report.append('\u2717').append(' ').append(command).append("（超时）\n");
                return false;
            }
            int exitCode = process.exitValue();
            boolean ok = exitCode == 0;
            report.append(ok ? '\u2713' : '\u2717').append(' ').append(command);
            String text = output.trim();
            if (!ok) {
                report.append("（退出码 ").append(exitCode);
                if (!text.isEmpty()) {
                    report.append("：").append(text);
                }
                report.append("）");
            } else if (!text.isEmpty() && !"success".equalsIgnoreCase(text)) {
                report.append("：").append(text);
            }
            report.append('\n');
            return ok;
        } catch (Throwable throwable) {
            report.append('\u2717').append(' ').append(command)
                    .append("（").append(throwable.getClass().getSimpleName()).append("）\n");
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String drain(InputStream input) {
        if (input == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (InputStream stream = input) {
            byte[] buffer = new byte[512];
            int length;
            while ((length = stream.read(buffer)) > 0) {
                // 超上限继续读但丢弃：break 会留下未排空的管道，子进程写满后卡死
                if (builder.length() <= 4000) {
                    builder.append(new String(buffer, 0, length));
                }
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "root stream read failed", throwable);
        }
        return builder.toString();
    }
}
