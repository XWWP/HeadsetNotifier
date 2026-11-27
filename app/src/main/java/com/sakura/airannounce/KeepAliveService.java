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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
    private static volatile long lastStartRequestMs;

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
    private int unboundTicks;
    private final android.os.Handler healthHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable listenerCheck = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            // 只有前台服务真的活着才刷新保护标记：模块据此区分「服务在线」与「进程残留的旧标记」。
            // 服务一旦停掉，这个 tick 随之消失，标记自然变旧，模块就会降频 —— 与 TAKEOVER 里
            // 「不要在通知心跳里刷 mtime」的约定一致（那条针对的是监听服务的心跳）。
            touchActiveFlag();
            // 先把「上次没做完的事」收尾，再判活：
            // 1) 切换绑定被打断 → 权限可能停在空的，靠 heal_inflight 这个磁盘标记补回；
            // 2) 音量提上去没还原 → 进程被冻住时 app 内定时器停摆，会一直冲耳朵。
            // 两件事都只看磁盘状态，进程重启后同样有效。
            ListenerHeal.recoverStuck(KeepAliveService.this);
            SpeechVolumeGuard.recoverIfNeeded(
                    KeepAliveService.this, NotificationReaderService.isSpeakingNow());
            // 用绑定标志而不是 getInstance()==null：后者在「服务对象还在、绑定已断」
            // 的窗口里会判成正常（本机实测该盲窗约 5 分钟）。
            if (!NotificationReaderService.isListenerBound()) {
                unboundTicks++;
                if (unboundTicks == ALERT_AFTER_TICKS) {
                    NotificationReaderService.recordEvent("提醒：监听已断开超过 "
                            + (ALERT_AFTER_TICKS * LISTENER_CHECK_INTERVAL_MS / 60_000L) + " 分钟");
                    ListenerAlert.post(KeepAliveService.this);
                }
                Log.w(TAG, "listener is not bound; self-healing.");
                // 先 Root（有 root 时唯一正确选择，不依赖任何外部 App），
                // 没 root 才用 Shizuku；两条通道都不可用才退回 requestRebind ——
                // 它在这类 ROM 上常是空操作。
                if (!ListenerHeal.healAsync(KeepAliveService.this)) {
                    NotificationReaderService.recordEvent("自检：监听未绑定，尝试重绑");
                    NotificationReaderService.requestRebindIfNeeded(KeepAliveService.this);
                }
            } else if (unboundTicks > 0) {
                unboundTicks = 0;
                ListenerAlert.cancel(KeepAliveService.this);
            }
            healthHandler.postDelayed(this, LISTENER_CHECK_INTERVAL_MS);
        }
    };

    static void start(Context context) {
        // running 只表示「这个进程里曾经成功进过前台」。服务被系统悄悄收掉、或进程
        // 重启后又回到 false，单看它会漏掉「标记残留但服务已不在」的状态，于是
        // start() 静默早退、保护标记永不刷新。改成 5 秒时间窗去重：窗口内重复请求
        // 忽略，窗口外再发一次 startForegroundService（幂等，重复调用只会重新走
        // 一遍 onStartCommand 并刷新标记）。
        long now = System.currentTimeMillis();
        if (running && now - lastStartRequestMs < 5000L) {
            return;
        }
        lastStartRequestMs = now;
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
        // 首次立即跑一次，而不是等满 60 秒。进程被拉起这个动作本身就说明「刚出过事」
        // （被冻住、被杀、装包导致解绑），这时候最该做的恰恰是补收尾：切换绑定窗口的
        // 授权、抬高却没还原的音量。等一分钟才检查，音量就在高位上多挂一分钟。
        healthHandler.post(listenerCheck);
    }

    private java.io.File activeFlagFile() {
        return new java.io.File(getFilesDir(), "active");
    }

    /**
     * 创建或刷新保护标记。内容是 `<毫秒戳> <0|1>`：
     * 第一段是新鲜度信号（模块按它判标记是否陈旧），第二段是「监听器当前是否绑定」。
     *
     * 为什么把绑定位塞进这个文件而不是单开一个 listener_bound：
     * 单独一个"只在事件里删改"的文件，进程被 SIGKILL 时不会删，模块会拿残留标记
     * 一直认为"已绑定"，从而永久跳过 dumpsys 复查与自愈（本机已实测复现）。
     * 这里的标记每 60 秒由保活 tick 重写一次，进程一死时间戳立刻变旧，
     * 模块按陈旧度自然失效，不需要任何"删除"动作。
     *
     * 第二段留空（旧版 app）时模块回落 dumpsys，保持向后兼容。
     */
    private void touchActiveFlag() {
        try {
            File flag = activeFlagFile();
            String content = System.currentTimeMillis()
                    + " " + (NotificationReaderService.isListenerBound() ? "1" : "0");
            try (FileOutputStream out = new FileOutputStream(flag, false)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            flag.setLastModified(System.currentTimeMillis());
        } catch (IOException ignored) {
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
        ListenerAlert.cancel(this);
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
