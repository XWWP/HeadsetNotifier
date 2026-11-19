package com.sakura.airannounce;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 「监听长时间没绑上」的提醒通知。
 *
 * 单独成一个类是因为它有两个发出点，而且这两个发出点都不能依赖前台服务：
 *   1. KeepAliveService 的 60 秒自检（前台服务在跑时）
 *   2. WakeReceiver（模块广播唤醒时）
 *
 * 2026-11-19 的死锁里前台服务根本起不来，第 1 个发出点等于不存在，
 * 所以提醒必须能从前台服务之外把话说完。
 */
final class ListenerAlert {
    private static final String TAG = "AirAnnounce";
    private static final String CHANNEL_ID = "airannounce_listener_alert";
    private static final int NOTIFICATION_ID = 1002;

    private ListenerAlert() {
    }

    static void post(Context context) {
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "监听掉线提醒",
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("通知监听断开后提醒你手动修复");
                manager.createNotificationChannel(channel);
            }
            Intent settings = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pending = PendingIntent.getActivity(
                    context, 0, settings,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            builder.setContentTitle("耳机播报暂时失效")
                    .setContentText("通知监听没有连上，点这里重新授权")
                    .setStyle(new Notification.BigTextStyle().bigText(
                            "通知监听没有连上，所以耳机里不会有播报。\n"
                                    + "点这条通知进入「通知使用权」，把「耳机通知播报」关一次再开一次即可。"))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (RuntimeException exception) {
            Log.w(TAG, "posting listener-down alert failed", exception);
        }
    }

    static void cancel(Context context) {
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
        } catch (RuntimeException ignored) {
        }
    }
}
