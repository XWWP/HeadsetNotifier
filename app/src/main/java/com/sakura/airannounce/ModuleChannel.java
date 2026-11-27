package com.sakura.airannounce;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 守护模块通道。
 *
 * 这是「有 root」的设备上唯一可靠的通道，理由是一条实测结论：
 * 本机以 app uid 执行 /system/bin/su 得到的是 ENOENT：
 * `sh: /system/bin/su: inaccessible or not found`，而 `ls` / `stat` 同一路径同样
 * 报 `No such file or directory` —— KernelSU 只对已授予 root 的 UID 做 su 的 exec
 * 重定向，未授权的 app uid 看不到该文件。所以「应用自己用 su 拿 root」在 KernelSU
 * 上走不通；原因不是 SELinux 拒绝执行（那会是 Permission denied），是文件对未授权 UID 不可见。
 *
 * 能走的只有反向的：应用往自己的私有目录写一个请求文件，以 root 运行的守护
 * （airannounce-thaw 模块）在自己的 5 秒节拍里读到就执行修复。
 * 应用写自己的 files 目录不需要任何特权，这条通道天然可用。
 *
 * 存活判定同样不读 /data/adb（untrusted_app 读不到）：守护会每分钟刷新
 * files/module_seen 的 mtime，应用看它新不新鲜就够了。
 */
final class ModuleChannel {
    private static final String TAG = "AirAnnounce";

    /** 应用写、守护读。内容是毫秒时间戳，守护靠内容变化去重。 */
    static final String REQUEST_FILE = "heal_request";
    /** 守护写、应用读。用作「模块活着」的心跳。 */
    static final String SEEN_FILE = "module_seen";

    /** module_seen 超过这个年龄就认为守护没在跑（守护每分钟刷一次）。 */
    private static final long SEEN_FRESH_MS = 3 * 60_000L;
    /** 请求节流：监听掉了不会立刻恢复，狂写只会让守护反复切换绑定。 */
    private static final long REQUEST_MIN_INTERVAL_MS = 60_000L;

    private static volatile long lastRequestAtMs;

    private ModuleChannel() {
    }

    /** 守护模块是否在跑。只看 files/module_seen 的新鲜度。 */
    static boolean isAlive(Context context) {
        try {
            File seen = new File(context.getFilesDir(), SEEN_FILE);
            if (!seen.isFile()) {
                return false;
            }
            long age = System.currentTimeMillis() - seen.lastModified();
            return age >= 0 && age < SEEN_FRESH_MS;
        } catch (Throwable throwable) {
            Log.w(TAG, "module alive check failed", throwable);
            return false;
        }
    }

    /**
     * 请求守护修一次监听绑定。
     *
     * @return true 表示请求已写入（守护会在下一个节拍处理）；false 表示模块不在跑或刚请求过
     */
    static boolean requestHeal(Context context) {
        if (!isAlive(context)) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastRequestAtMs < REQUEST_MIN_INTERVAL_MS) {
            return false;
        }
        File dir = context.getFilesDir();
        File target = new File(dir, REQUEST_FILE);
        File temp = new File(dir, REQUEST_FILE + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(temp);
            out.write(String.valueOf(System.currentTimeMillis()).getBytes("UTF-8"));
            out.flush();
            // 落盘再 rename：守护随时可能来读，读到半个时间戳会当成新请求反复触发
            out.getFD().sync();
            out.close();
            out = null;
            if (!temp.renameTo(target)) {
                Log.w(TAG, "heal request rename failed");
                temp.delete();
                return false;
            }
            lastRequestAtMs = now;
            Log.i(TAG, "heal request written");
            return true;
        } catch (IOException exception) {
            Log.w(TAG, "heal request failed", exception);
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 已经在异常路径上，关闭失败没有补救手段
                }
            }
        }
    }
}
