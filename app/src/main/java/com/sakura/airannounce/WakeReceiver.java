package com.sakura.airannounce;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 仅供 root 守护脚本调用的唤醒入口（显式广播）。
 *
 * 为什么需要它：
 * - KeepAliveService 是 exported="false"，脚本用 am start*-service 跨 uid 启动会被
 *   系统拒绝（实测 ColorOS 返回 "Not found; no service started."）；
 * - am start-activity 拉起主界面会切到前台并点亮屏幕，打扰用户。
 *
 * 这里提供一个导出的显式广播：脚本 am broadcast 过来，进程被拉起后在本进程内启动
 * 前台服务，既不弹界面也不亮屏。是否真的需要保活交给 syncKeepAlive 复核——
 * 它尊重用户的播报开关，也只在当前确实有蓝牙音频输出时才启动服务。
 */
public class WakeReceiver extends BroadcastReceiver {
    private static final String TAG = "AirAnnounce";

    /** 监听已断多久后开始提醒；提醒间隔 6 小时，避免刷屏。 */
    private static final long ALERT_AFTER_MS = 10 * 60_000L;
    private static final long ALERT_REPEAT_MS = 60 * 60_000L;
    private static final String PREF_DOWN_SINCE = "listener_down_since_ms";
    private static final String PREF_LAST_ALERT = "listener_alert_last_ms";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "WakeReceiver: keep-alive requested by root helper.");
        MainActivity.syncKeepAlive(context);
        // 这条路径不依赖前台服务：2026-11-19 的死锁里前台服务起不来，
        // 只有广播还能到达，所以修监听和发提醒都得在这儿也做一遍。
        if (!ShizukuChannel.healListenerAsync(context)) {
            NotificationReaderService.requestRebindIfNeeded(context);
        }
        maybeAlert(context);
    }

    /**
     * 监听断了超过 10 分钟就喊一声。
     * 2026-11-19 那次哑火 20 分钟没人知道，用户只能自己去翻诊断页。
     */
    private static void maybeAlert(Context context) {
        boolean bound = NotificationReaderService.getInstance() != null;
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (bound) {
            prefs.edit().remove(PREF_DOWN_SINCE).apply();
            ListenerAlert.cancel(context);
            return;
        }
        long now = System.currentTimeMillis();
        long downSince = prefs.getLong(PREF_DOWN_SINCE, 0L);
        if (downSince == 0L) {
            prefs.edit().putLong(PREF_DOWN_SINCE, now).apply();
            return;
        }
        if (now - downSince < ALERT_AFTER_MS) {
            return;
        }
        long lastAlert = prefs.getLong(PREF_LAST_ALERT, 0L);
        if (lastAlert != 0L && now - lastAlert < ALERT_REPEAT_MS) {
            return;
        }
        prefs.edit().putLong(PREF_LAST_ALERT, now).apply();
        NotificationReaderService.recordEvent("提醒：监听已断开超过 10 分钟，已发通知");
        ListenerAlert.post(context);
    }
}
