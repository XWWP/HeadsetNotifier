package com.sakura.airannounce;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 仅在蓝牙音频设备连接期间存活的前台服务。
 *
 * 目的：给进程一个稳定的前台服务状态，避免被厂商 ROM（一加/ColorOS 等）的
 * 后台冻结策略回收，从而保证 NotificationListenerService 能持续收到通知回调。
 * 蓝牙断开后会由 BluetoothStateReceiver 主动停止，通知随之消失。
 */
public class KeepAliveService extends Service {
    private static final String TAG = "AirAnnounce";
    private static final String CHANNEL_ID = "airannounce_keepalive_v2";
    private static final int NOTIFICATION_ID = 1001;

    private static volatile boolean running;

    static boolean isRunning() {
        return running;
    }

    /**
     * 监听掉线的自检周期。
     *
     * 这个检查必须放在保活服务里而不是监听服务里：监听服务被解绑时它自己已经不在了，
     * 没有"外部视角"能发现这件事。保活服务在前台、耳机连着时一直活着，正好可以当这个视角。
     * 60 秒一次只是读一个静态字段，代价可以忽略。
     */
    private static final long LISTENER_CHECK_INTERVAL_MS = 60_000L;
    /**
     * 监听连续断多久就发提醒。
     * 2026-11-12 现场：监听掉了 23 分钟，用户在诊断页才看出来 —— 这种"静默哑火"
     * 对使用者来说最难查，至少要先喊一声。10 分钟是权衡：够长，自动修复
     * （模块 60 秒一轮 + 重试）基本都已经失败；够短，不至于一天没人管。
     */
    private static final int ALERT_AFTER_TICKS = 10;
    private static final String ALERT_CHANNEL_ID = "airannounce_listener_alert";
    private static final int ALERT_NOTIFICATION_ID = 1002;
    private int unboundTicks;
    private final android.os.Handler healthHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable listenerCheck = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (NotificationReaderService.getInstance() == null) {
                unboundTicks++;
                if (unboundTicks == ALERT_AFTER_TICKS) {
                    notifyListenerDown();
                }
                Log.w(TAG, "listener is not bound; self-healing.");
                // 优先走 Shizuku 的 disallow/allow 切换：requestRebind 在部分 ROM 上是空操作，
                // 而切换是实测唯一稳定有效的手段。没有可用 Shizuku 时才退回 requestRebind。
                if (!ShizukuChannel.healListenerAsync(KeepAliveService.this)) {
                    NotificationReaderService.recordEvent("自检：监听未绑定，尝试重绑");
                    NotificationReaderService.requestRebindIfNeeded(KeepAliveService.this);
                }
            } else if (unboundTicks > 0) {
                unboundTicks = 0;
                cancelListenerAlert();
            }
            healthHandler.postDelayed(this, LISTENER_CHECK_INTERVAL_MS);
        }
    };

    static void start(Context context) {
        if (running) {
            return;
        }
        try {
            Intent intent = new Intent(context, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.i(TAG, "KeepAliveService start requested.");
        } catch (RuntimeException exception) {
            // Android 12+ 后台启动前台服务可能被系统拒绝，这里只记录不崩溃。
            Log.w(TAG, "KeepAliveService start rejected by system", exception);
        }
    }

    static void stop(Context context) {
        try {
            // onDestroy 要等系统调度，这里先落标志，避免 stop 与重连广播之间的
            // check-then-act 竞态：stop 期间收到蓝牙重连会被 running == true 挡掉。
            running = false;
            context.stopService(new Intent(context, KeepAliveService.class));
            Log.i(TAG, "KeepAliveService stop requested.");
        } catch (RuntimeException exception) {
            Log.w(TAG, "KeepAliveService stop failed", exception);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 无 root 但装了 Shizuku 时，顺手把 standby bucket / 电池白名单补一遍
        ShizukuChannel.applyQuietlyIfEnabled(this);
        createNotificationChannel();
        // 标记"当前需要防冻结保护"，供 system 层的解冻守护脚本读取。
        // 没有这个文件时，守护脚本会降到极低频率，避免无谓唤醒。
        touchActiveFlag();
        // 开机或进程被重新拉起后，通知监听服务不一定处于绑定状态，主动请求一次重绑。
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> NotificationReaderService.requestRebindIfNeeded(this), 1500L);
        healthHandler.removeCallbacks(listenerCheck);
        healthHandler.postDelayed(listenerCheck, LISTENER_CHECK_INTERVAL_MS);
    }

    private java.io.File activeFlagFile() {
        return new java.io.File(getFilesDir(), "active");
    }

    /**
     * 创建或刷新保护标记。
     * 守护脚本用 mtime 判断这个标记背后还有没有活着的进程：标记只由 app 写，
     * 模块只读不删，这样不会出现"模块删了标记、app 没重建导致永久不保护"。
     */
    private void touchActiveFlag() {
        try {
            java.io.File flag = activeFlagFile();
            if (!flag.exists()) {
                flag.createNewFile();
            }
            flag.setLastModified(System.currentTimeMillis());
        } catch (java.io.IOException ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            Log.i(TAG, "KeepAliveService is now foreground.");
            NotificationReaderService.recordEvent("保活服务已启动");
        } catch (RuntimeException exception) {
            Log.e(TAG, "startForeground failed", exception);
            NotificationReaderService.recordEvent("保活服务启动失败：" + exception.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        // 每次被拉起（包括模块主动 wake_app）都刷新一次，作为"标记还活着"的信号
        touchActiveFlag();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        // 移除保护标记：守护脚本看到文件消失后会降频。
        try {
            activeFlagFile().delete();
        } catch (RuntimeException ignored) {
        }
        healthHandler.removeCallbacksAndMessages(null);
        cancelListenerAlert();
        Log.i(TAG, "KeepAliveService destroyed.");
        NotificationReaderService.recordEvent("保活服务已停止");
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException exception) {
            Log.w(TAG, "stopForeground failed", exception);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 监听长时间没绑上时喊一声，并直接跳到通知使用权设置页。
     *
     * 这条路不依赖 root 也不依赖 Shizuku，是"自动修复全失败"之后的最后兜底：
     * 至少让用户知道现在念不出来，而不是等到某天发现一整天都没声。
     */
    private void notifyListenerDown() {
        NotificationReaderService.recordEvent("提醒：监听已断开超过 "
                + (ALERT_AFTER_TICKS * LISTENER_CHECK_INTERVAL_MS / 60_000L) + " 分钟");
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        ALERT_CHANNEL_ID,
                        "监听掉线提醒",
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("通知监听断开后提醒你手动修复");
                manager.createNotificationChannel(channel);
            }
            android.content.Intent settings = new android.content.Intent(
                    android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            settings.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pending = android.app.PendingIntent.getActivity(
                    this, 0, settings,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, ALERT_CHANNEL_ID)
                    : new Notification.Builder(this);
            builder.setContentTitle("耳机播报暂时失效")
                    .setContentText("通知监听没有连上，点这里重新授权")
                    .setStyle(new Notification.BigTextStyle().bigText(
                            "通知监听没有连上，所以耳机里不会有播报。\n"
                                    + "点这条通知进入「通知使用权」，把「耳机通知播报」关一次再开一次即可。"))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true);
            manager.notify(ALERT_NOTIFICATION_ID, builder.build());
        } catch (RuntimeException exception) {
            Log.w(TAG, "posting listener-down alert failed", exception);
        }
    }

    private void cancelListenerAlert() {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(ALERT_NOTIFICATION_ID);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.keepalive_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle(getString(R.string.keepalive_title))
                .setContentText(getString(R.string.keepalive_text))
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }
}
