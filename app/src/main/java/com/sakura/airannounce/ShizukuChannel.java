package com.sakura.airannounce;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.InputStream;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Shizuku / Sui 降级通道。
 *
 * 定位：没有 root、但装了 Shizuku 的设备上，用 shell 权限把系统这一侧能做的
 * 事情补上（重绑通知监听、standby bucket、电池白名单）。
 *
 * 明确做不到的事：解冻被 ColorOS 放进 cgroup freezer 的进程。
 * 解冻需要写 /sys/fs/cgroup/.../cgroup.freeze，shell uid 没有那个权限，
 * 只有 root 能做 —— 所以这个通道永远只是 root 守护脚本的补充，不是替代品。
 */
public final class ShizukuChannel {
    private static final String TAG = "AirAnnounce";

    static final String KEY_ENHANCE = "shizuku_enhance_enabled";
    static final String KEY_LAST_APPLIED = "shizuku_last_applied_at";
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    static final int REQUEST_CODE = 2311;

    public enum State {
        /** 设备上没装 Shizuku */
        NOT_INSTALLED,
        /** 装了但 Binder 没起来（没启动 / 无线调试没配对 / 重启后没重新激活） */
        NOT_RUNNING,
        /** Binder 在，但本应用还没被授权 */
        NOT_GRANTED,
        /** 可用 */
        READY
    }

    private ShizukuChannel() {
    }

    static State state(Context context) {
        if (!isInstalled(context)) {
            return State.NOT_INSTALLED;
        }
        try {
            if (!Shizuku.pingBinder()) {
                // 进程刚启动时 Shizuku 的 provider 还没来得及把 Binder 递过来，
                // 直接判定会误报「未运行」：用户点「打开 Shizuku」跳过去，
                // 看到的是一个一切正常的 Shizuku，自然觉得「没反应」。
                awaitBinder(1500L);
            }
            if (!Shizuku.pingBinder()) {
                return State.NOT_RUNNING;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? State.READY
                    : State.NOT_GRANTED;
        } catch (Throwable throwable) {
            // Binder 刚死、服务端版本过旧等都会走到这里，一律当成"没跑起来"
            Log.w(TAG, "Shizuku state check failed", throwable);
            return State.NOT_RUNNING;
        }
    }

    /**
     * 等 Shizuku 的 Binder 到达（最多 timeoutMs）。
     * addBinderReceivedListenerSticky 在 Binder 已就绪时会立刻回调，所以正常情况零开销。
     */
    private static void awaitBinder(long timeoutMs) {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        rikka.shizuku.Shizuku.OnBinderReceivedListener listener = latch::countDown;
        try {
            Shizuku.addBinderReceivedListenerSticky(listener);
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable throwable) {
            Log.w(TAG, "waiting for Shizuku binder failed", throwable);
        } finally {
            try {
                Shizuku.removeBinderReceivedListener(listener);
            } catch (Throwable ignored) {
            }
        }
    }

    static String describe(State state) {
        switch (state) {
            case READY:
                return "Shizuku 已连接并授权";
            case NOT_GRANTED:
                return "Shizuku 已连接，尚未授权";
            case NOT_RUNNING:
                return "已安装，但 Shizuku 未运行（重启后需重新激活）";
            case NOT_INSTALLED:
            default:
                return "未安装 Shizuku";
        }
    }

    private static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
    }

    /** 弹出 Shizuku 授权请求，结果通过 Shizuku 的权限回调返回。 */
    static boolean requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            Shizuku.requestPermission(REQUEST_CODE);
            return true;
        } catch (Throwable throwable) {
            Log.w(TAG, "Shizuku requestPermission failed", throwable);
            return false;
        }
    }

    /**
     * 立即应用一次降级保活设置，返回给用户看的执行报告。
     * 内部有 Binder 调用，必须在后台线程调用。
     */
    static String apply(Context context) {
        if (state(context) != State.READY) {
            return "Shizuku 当前不可用：" + describe(state(context));
        }
        IShizukuService service;
        try {
            service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        } catch (Throwable throwable) {
            return "无法取得 Shizuku 服务：" + throwable.getClass().getSimpleName();
        }
        if (service == null) {
            return "无法取得 Shizuku 服务";
        }

        String packageName = context.getPackageName();
        String listener = packageName + "/" + NotificationReaderService.class.getName();
        StringBuilder report = new StringBuilder();

        // 1. 电池优化白名单（等价于系统设置里的"不限制后台"）
        run(service, "cmd deviceidle whitelist +" + packageName, report);
        // 2. 把 standby bucket 拉到 active，避免被当成长期不用而降权
        run(service, "am set-standby-bucket " + packageName + " active", report);
        // 3. 重绑通知监听服务：系统回调没送达时最有效的一招
        run(service, "cmd notification allow_listener " + listener, report);

        if (report.indexOf("\u2717") < 0) {
            context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_APPLIED, System.currentTimeMillis())
                    .apply();
        }
        return report.toString().trim();
    }

    /**
     * 开关打开时在服务启动 / 耳机连接后静默补一遍。
     * 只做幂等的两条，不碰 notification allow_listener：
     * 那条会在播报过程中打断监听连接，只适合用户手动触发。
     */
    static void applyQuietlyIfEnabled(final Context context) {
        final SharedPreferences preferences =
                context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_ENHANCE, false)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (state(appContext) != State.READY) {
                    return;
                }
                IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
                if (service == null) {
                    return;
                }
                String packageName = appContext.getPackageName();
                StringBuilder ignored = new StringBuilder();
                run(service, "cmd deviceidle whitelist +" + packageName, ignored);
                run(service, "am set-standby-bucket " + packageName + " active", ignored);
                preferences.edit()
                        .putLong(KEY_LAST_APPLIED, System.currentTimeMillis())
                        .apply();
            } catch (Throwable throwable) {
                Log.w(TAG, "Shizuku quiet apply failed", throwable);
            }
        }, "shizuku-quiet-apply").start();
    }

    private static boolean run(IShizukuService service, String command, StringBuilder report) {
        try {
            IRemoteProcess process = service.newProcess(
                    new String[]{"sh", "-c", command}, null, null);
            if (process == null) {
                report.append("\u2717 ").append(command).append("（无返回进程）\n");
                return false;
            }
            // 两个流必须并行排空：顺序读时若 stderr 先填满管道，子进程会卡在
            // 写 stderr 上，而我们在等 stdout 读完，waitFor 就永远挂起。
            final String[] out = {""};
            final String[] err = {""};
            Thread outThread = new Thread(() -> {
                try {
                    out[0] = read(process.getInputStream());
                } catch (Throwable ignored) {
                }
            }, "shizuku-out");
            Thread errThread = new Thread(() -> {
                try {
                    err[0] = read(process.getErrorStream());
                } catch (Throwable ignored) {
                }
            }, "shizuku-err");
            outThread.start();
            errThread.start();
            int exitCode = process.waitFor();
            outThread.join(3000L);
            errThread.join(3000L);
            boolean ok = exitCode == 0;
            report.append(ok ? "\u2713 " : "\u2717 ").append(command);
            if (!ok) {
                report.append("（退出码 ").append(exitCode);
                if (!err[0].isEmpty()) {
                    report.append("：").append(err[0]);
                }
                report.append("）");
            } else if (!out[0].isEmpty() && !out[0].equalsIgnoreCase("success")) {
                report.append("：").append(out[0]);
            }
            report.append('\n');
            return ok;
        } catch (Throwable throwable) {
            Log.w(TAG, "Shizuku command failed: " + command, throwable);
            report.append("\u2717 ").append(command)
                    .append("（").append(throwable.getClass().getSimpleName()).append("）\n");
            return false;
        }
    }

    private static String read(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[512];
            int length;
            while ((length = input.read(buffer)) > 0) {
                // 超上限继续读但丢弃：break 会留下未排空的管道，
                // 子进程写满缓冲区后卡死，waitFor 跟着永远挂起。
                if (builder.length() <= 2000) {
                    builder.append(new String(buffer, 0, length));
                }
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Shizuku read stream failed", throwable);
        }
        return builder.toString().trim();
    }
}
