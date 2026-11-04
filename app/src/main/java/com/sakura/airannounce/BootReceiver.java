package com.sakura.airannounce;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启：重启后若耳机已连接，直接把保活服务拉起来。
 * 通知监听服务本身由系统在开机后自动绑定，这里只补前台服务。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AirAnnounce";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_ENABLED, true)) {
            Log.i(TAG, "announcements disabled; skip keep-alive on boot.");
            return;
        }
        if (!MainActivity.hasBluetoothAudioOutput(context)) {
            return;
        }
        Log.i(TAG, "boot completed with bluetooth audio, restoring services");
        KeepAliveService.start(context);
        // 系统开机后不一定会立刻绑定通知监听服务，主动请求一次重绑。
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> NotificationReaderService.requestRebindIfNeeded(context), 3000L);
    }
}
