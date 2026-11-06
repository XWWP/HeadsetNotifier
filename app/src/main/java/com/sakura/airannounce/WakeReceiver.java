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

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "WakeReceiver: keep-alive requested by root helper.");
        MainActivity.syncKeepAlive(context);
        NotificationReaderService.requestRebindIfNeeded(context);
    }
}
