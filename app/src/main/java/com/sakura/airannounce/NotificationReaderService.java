package com.sakura.airannounce;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class NotificationReaderService extends NotificationListenerService {
    private static final String TAG = "AirAnnounce";
    private static final long DUPLICATE_WINDOW_MS = 120_000L;
    private static final int MAX_PENDING_ANNOUNCEMENTS = 5;
    private static final int MAX_BODY_LENGTH = 300;
    /** 队列里超过这个年龄的内容直接丢弃，避免念出几分钟前的旧消息。 */
    private static final long MAX_QUEUE_AGE_MS = 30_000L;
    private static final long UTTERANCE_TIMEOUT_MS = 60_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    /** 服务销毁后必须停掉轮询，否则 finally 里的 postDelayed 会让它在销毁后继续空转。 */
    private volatile boolean destroyed;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            // 正常情况下无论如何都要把下一次轮询排上，否则一次异常就会让轮询链永久断掉。
            lastPollTickMs = SystemClock.elapsedRealtime();
            try {
                writeHeartbeat();
                pollActiveNotifications();
            } catch (Throwable throwable) {
                Log.e(TAG, "poll tick failed", throwable);
            } finally {
                if (!destroyed) {
                    pollHandler.postDelayed(this, pollInterval());
                }
            }
        }
    };

    /** 轮询间隔可在设置里调整，默认 5 秒（1–30 秒可选）。 */
    private long pollInterval() {
        int value = getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .getInt(MainActivity.KEY_POLL_INTERVAL, MainActivity.DEFAULT_POLL_INTERVAL_MS);
        if (value < 1000) {
            value = 1000;
        }
        if (value > 30000) {
            value = 30000;
        }
        // 自适应：如果最近 2 分钟收到过系统回调，说明推送通道是通的
        // （例如有外部守护脚本防止了进程冻结），此时轮询只当兜底，大幅降频省电。
        long sinceCallback = SystemClock.elapsedRealtime() - lastCallbackMs;
        if (lastCallbackMs > 0 && sinceCallback < 120_000L) {
            return Math.max(value, 15_000L);
        }
        return value;
    }

    /**
     * 空闲期唤醒周期：耳机不在时彻底停掉轮询，只按这个周期醒一次。
     * 两个作用：① 让「进程还活着」的心跳不至于长期断档（写入本来就被 3 分钟节流卡着，
     * 远小于 7 分钟的断档判定阈值）；② 万一某个 ROM 不发音频设备回调，
     * 这里能在 2 分钟内发现「其实耳机连着」，把轮询救回来。
     */
    private static final long IDLE_HEARTBEAT_MS = 120_000L;
    /** 轮询循环是否在跑。耳机断开后为 false，此时 pollHandler 上只挂着空闲心跳。 */
    private volatile boolean pollingActive;
    /** 上一次轮询 tick 的时刻，用来证明"轮询是不是真的停了"。 */
    private long lastPollTickMs;
    private final Runnable idleHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            writeHeartbeat();
            if (checkBluetoothFresh()) {
                Log.i(TAG, "Idle watchdog: bluetooth audio back, resuming polling.");
                recordEvent("空闲看门狗：耳机已连接，恢复轮询");
                startPolling(1000L);
                return;
            }
            pollHandler.postDelayed(this, IDLE_HEARTBEAT_MS);
        }
    };

    /** 开始（或恢复）轮询。已经在跑时不动，避免连续几条设备广播把倒计时反复重置。 */
    private void startPolling(long delayMs) {
        pollHandler.removeCallbacks(idleHeartbeatRunnable);
        if (pollingActive) {
            return;
        }
        pollingActive = true;
        long delay = Math.max(0L, delayMs);
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.postDelayed(pollRunnable, delay);
        Log.i(TAG, "Polling started, first tick in " + delay + "ms.");
    }

    /**
     * 停掉轮询循环。耳机不在时没有任何播报需求，5 秒一次的 tick 纯属耗电：
     * 它会持续唤醒进程，让 App 进不了深度空闲。停掉后只留 2 分钟的空闲心跳。
     *
     * 同时复位轮询基线（pollInitialized / 指纹表），否则耳机重新连上后
     * 会把断开期间堆在通知栏里的旧通知补念一遍。
     */
    private void stopPolling() {
        boolean wasActive = pollingActive;
        pollHandler.removeCallbacks(pollRunnable);
        pollingActive = false;
        resetPollingState();
        writeHeartbeat();
        if (!destroyed) {
            pollHandler.removeCallbacks(idleHeartbeatRunnable);
            pollHandler.postDelayed(idleHeartbeatRunnable, IDLE_HEARTBEAT_MS);
        }
        if (wasActive) {
            Log.i(TAG, "Polling suspended (no bluetooth audio output).");
            recordEvent("轮询已暂停（未连接耳机）");
        }
    }

    /** 让轮询状态跟「开关 + 耳机」两个条件对齐，只在真正的边沿上启停。 */
    private void syncPollingWithBluetooth() {
        boolean enabled = getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_ENABLED, true);
        if (!enabled || !checkBluetoothFresh()) {
            stopPolling();
            return;
        }
        startPolling(1000L);
    }

    /** 设置页改完开关后从外部对齐一次（播报关掉时同样要停轮询）。 */
    static void syncPollingIfAvailable() {
        NotificationReaderService service = instance;
        if (service == null) {
            return;
        }
        service.mainHandler.post(service::syncPollingWithBluetooth);
    }

    private final ArrayDeque<PendingAnnouncement> pendingAnnouncements = new ArrayDeque<>();
    private final Map<String, SpokenNotification> recentNotifications = new HashMap<>();

    private final Map<String, String> lastFingerprints = new HashMap<>();
    private final Map<String, String> appLabelCache = new HashMap<>();

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private ToneGenerator toneGenerator;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private int ttsInitRetries;
    private static final int MAX_TTS_INIT_RETRIES = 3;
    private int savedMusicVolume = -1;
    /** 正在播报中的 utteranceId。用集合而非计数，避免 TTS 同时回调 onDone/onStop 把计数减成负数。 */
    private final Set<String> activeUtterances = new HashSet<>();
    /** 音量提升后的兜底恢复：无论回调是否到达，超时一定恢复原音量。 */
    /** 超时守卫：固定引用才能 removeCallbacks。 */
    private final Runnable utteranceTimeoutGuard = this::onUtteranceTimeoutGuard;
    private final Runnable volumeSafetyRestore = new Runnable() {
        @Override
        public void run() {
            if (savedMusicVolume >= 0) {
                Log.w(TAG, "volume safety restore fired.");
                restoreVolumeAfterSpeech();
            }
        }
    };
    private static final long VOLUME_SAFETY_TIMEOUT_MS = 120_000L;

    /** 最近处理记录：让用户（含未 root）能自己在应用里看到每条通知为什么没播。 */
    private static final java.util.LinkedList<String> RECENT_EVENTS = new java.util.LinkedList<>();
    private static final int MAX_RECENT_EVENTS = 60;
    /** 非线程安全，所有使用点都在 RECENT_EVENTS 锁内。 */
    private static final java.text.SimpleDateFormat EVENT_STAMP =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());

    static void recordEvent(String event) {
        synchronized (RECENT_EVENTS) {
            RECENT_EVENTS.addFirst(EVENT_STAMP.format(new java.util.Date()) + "  " + event);
            while (RECENT_EVENTS.size() > MAX_RECENT_EVENTS) {
                RECENT_EVENTS.removeLast();
            }
        }
    }

    static java.util.List<String> getRecentEvents() {
        synchronized (RECENT_EVENTS) {
            return new java.util.ArrayList<>(RECENT_EVENTS);
        }
    }

    static void clearRecentEvents() {
        synchronized (RECENT_EVENTS) {
            RECENT_EVENTS.clear();
        }
        synchronized (RECENT_SKIPS) {
            RECENT_SKIPS.clear();
        }
    }

    /** 跳过原因去重：同一应用同一原因 60 秒内只记一次，否则轮询会把记录刷爆。 */
    private static final java.util.Map<String, Long> RECENT_SKIPS = new java.util.HashMap<>();

    static void recordSkip(String packageName, String reason) {
        String key = packageName + "|" + reason;
        long now = SystemClock.elapsedRealtime();
        synchronized (RECENT_SKIPS) {
            Long last = RECENT_SKIPS.get(key);
            if (last != null && now - last < 60_000L) {
                return;
            }
            // 长时间运行后条目会慢慢累积，超过上限就整体清空，避免内存无界增长
            if (RECENT_SKIPS.size() > 400) {
                RECENT_SKIPS.clear();
            }
            RECENT_SKIPS.put(key, now);
        }
        recordEvent("跳过 " + packageName + "：" + reason);
    }

    static boolean isServiceRunning() {
        return instance != null;
    }

    /** 记录通知是怎么进来的：系统回调 / 轮询补拉。用来区分"没收到"和"收到没播"。 */
    private static final java.util.Map<String, Long> RECENT_ARRIVALS = new java.util.HashMap<>();

    static void recordArrival(String packageName, String source) {
        String key = packageName + "|" + source;
        long now = SystemClock.elapsedRealtime();
        synchronized (RECENT_ARRIVALS) {
            Long last = RECENT_ARRIVALS.get(key);
            if (last != null && now - last < 60_000L) {
                return;
            }
            if (RECENT_ARRIVALS.size() > 400) {
                RECENT_ARRIVALS.clear();
            }
            RECENT_ARRIVALS.put(key, now);
        }
        recordEvent(source + " " + packageName);
    }

    // ---------------- 存活心跳与中断检测 ----------------

    private static final String PREF_HEARTBEAT_WALL = "heartbeat_wall_ms";
    private static final String PREF_HEARTBEAT_ELAPSED = "heartbeat_elapsed_ms";
    private static final String PREF_INTERRUPTION_LOG = "interruption_log";
    /** 超过这个间隔没有心跳，就认为进程在这段时间里不在了。 */
    private static final long HEARTBEAT_GAP_THRESHOLD_MS = 420_000L;
    /** 心跳写盘节流：每次都是一次 XML 重写 + fsync，60 秒太频繁了。 */
    private static final long HEARTBEAT_WRITE_INTERVAL_MS = 180_000L;
    private long lastHeartbeatWriteMs;

    /**
     * 写一次心跳。进程被 SIGKILL 时内存日志会全丢、也来不及落盘，
     * 所以只能靠"下次启动时发现心跳有断档"来反推曾经被杀。
     */
    private void writeHeartbeat() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastHeartbeatWriteMs < HEARTBEAT_WRITE_INTERVAL_MS) {
            return;
        }
        lastHeartbeatWriteMs = now;
        getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putLong(PREF_HEARTBEAT_WALL, System.currentTimeMillis())
                .putLong(PREF_HEARTBEAT_ELAPSED, now)
                .apply();
    }

    /** 启动时对比上次心跳，把断档记录成一条"疑似中断"。 */
    private void detectInterruption() {
        android.content.SharedPreferences prefs =
                getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE);
        long lastWall = prefs.getLong(PREF_HEARTBEAT_WALL, 0L);
        long lastElapsed = prefs.getLong(PREF_HEARTBEAT_ELAPSED, 0L);
        long nowElapsed = SystemClock.elapsedRealtime();
        if (lastWall <= 0L || lastElapsed <= 0L) {
            return;
        }
        // 重启过设备：两个时钟对不上，无法判断，跳过
        if (nowElapsed < lastElapsed) {
            return;
        }
        long gap = System.currentTimeMillis() - lastWall;
        if (gap < HEARTBEAT_GAP_THRESHOLD_MS) {
            return;
        }
        String from = new java.text.SimpleDateFormat("HH:mm",
                java.util.Locale.getDefault()).format(new java.util.Date(lastWall));
        String to = new java.text.SimpleDateFormat("HH:mm",
                java.util.Locale.getDefault()).format(new java.util.Date());
        long minutes = gap / 60000L;
        String line = from + " → " + to + "  中断约 " + minutes + " 分钟";
        recordEvent("服务中断：" + line);
        String existing = prefs.getString(PREF_INTERRUPTION_LOG, "");
        String updated = line + (existing.isEmpty() ? "" : "\n" + existing);
        String[] lines = updated.split("\\n");
        StringBuilder trimmed = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 8); i++) {
            if (i > 0) {
                trimmed.append('\n');
            }
            trimmed.append(lines[i]);
        }
        prefs.edit().putString(PREF_INTERRUPTION_LOG, trimmed.toString()).apply();
    }

    static String getInterruptionLog(Context context) {
        return context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(PREF_INTERRUPTION_LOG, "");
    }

    /**
     * 诊断用：把关键运行时状态拼成几行文本。
     * 用户看不到 logcat，排查「为什么不播」只能靠这些状态 + 最近处理记录。
     */
    static String describeRuntime() {
        NotificationReaderService service = instance;
        if (service == null) {
            return "· 监听服务：未运行";
        }
        long sinceCallback = service.lastCallbackMs <= 0
                ? -1L
                : SystemClock.elapsedRealtime() - service.lastCallbackMs;
        StringBuilder builder = new StringBuilder();
        builder.append("· TTS：").append(service.ttsReady ? "就绪" : "未就绪").append('\n');
        builder.append("· 待播队列：").append(service.pendingAnnouncements.size())
                .append(" 条，播放中：").append(service.activeUtterances.size()).append(" 条\n");
        long sinceTick = service.lastPollTickMs <= 0L
                ? -1L
                : SystemClock.elapsedRealtime() - service.lastPollTickMs;
        builder.append("· 轮询：").append(service.pollingActive
                        ? ("运行中，间隔 " + (service.pollInterval() / 1000f) + " 秒")
                        : "已暂停（未连接耳机）")
                .append(sinceTick < 0 ? "，尚未跑过" : "，上次 tick " + (sinceTick / 1000L) + " 秒前")
                .append('\n');
        builder.append("· 最近系统回调：")
                .append(sinceCallback < 0 ? "尚未收到" : (sinceCallback / 1000L) + " 秒前")
                .append('\n');
        builder.append("· 保活服务：").append(KeepAliveService.isRunning() ? "运行中" : "未运行");
        return builder.toString();
    }

    static void clearInterruptionLog(Context context) {
        context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_INTERRUPTION_LOG)
                .remove(PREF_HEARTBEAT_WALL)
                .remove(PREF_HEARTBEAT_ELAPSED)
                .apply();
    }
    private static volatile NotificationReaderService instance;
    private boolean pollInitialized;
    private long lastPollLogMs;
    private long lastCallbackMs;
    private long lastBluetoothCheckMs;
    private volatile boolean lastBluetoothState;
    /** 音频设备回调已经给出过状态时置 true，之后不再定时查 binder。 */
    private volatile boolean bluetoothStateFromCallback;
    /** 兜底：万一某个 ROM 不发设备回调，最多 10 分钟强制重查一次。 */
    private static final long BLUETOOTH_STATE_MAX_AGE_MS = 600_000L;

    private final AudioDeviceCallback audioDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            refreshBluetoothStateFromCallback();
            // 耳机接上了：轮询和 TTS 引擎到这时候才真正需要存在。
            if (lastBluetoothState) {
                ensureTtsInitialized();
                startPolling(1000L);
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            refreshBluetoothStateFromCallback();
            if (!lastBluetoothState) {
                Log.i(TAG, "Bluetooth audio output removed; clearing queue and stopping speech.");
                recordEvent("蓝牙音频已断开（队列已清空）");
                pendingAnnouncements.clear();
                activeUtterances.clear();
                if (textToSpeech != null) {
                    textToSpeech.stop();
                    // 拔了耳机就没人听：把引擎整个放掉，否则它会一直 bind 在本进程里
                    textToSpeech.shutdown();
                    textToSpeech = null;
                    ttsReady = false;
                }
                // 不能只等 TTS 回调来恢复音量：部分引擎对被排队的 utterance
                // 根本不回调 onStop，音量会卡在高位最长 2 分钟。
                abandonAudioFocus();
                restoreVolumeAfterSpeech();
                // 没有耳机就没人听：轮询也一起停掉，只留空闲心跳。
                stopPolling();
            }
        }
    };

    static void requestRebindIfNeeded(Context context) {
        try {
            android.content.ComponentName component =
                    new android.content.ComponentName(context, NotificationReaderService.class);
            NotificationListenerService.requestRebind(component);
            Log.i(TAG, "requestRebind issued.");
        } catch (RuntimeException exception) {
            Log.w(TAG, "requestRebind failed", exception);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate.");
        instance = this;
        detectInterruption();
        recordEvent("监听服务已启动");
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler);
        }
        // TTS 不再无条件初始化：只在检测到耳机连接时才绑定引擎，避免空跑占资源。
        ensureKeepAliveIfNeeded();
        // 轮询放在这里而不是前台服务里：NotificationListenerService 由系统 bindService 绑定，
        // 生命周期比自建的前台服务稳定得多，被回收的概率低。
        // 只在耳机已经连着时才开跑：没有耳机就没有播报需求，没必要每 5 秒醒一次。
        syncPollingWithBluetooth();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "onListenerConnected: listener is bound and ready.");
        recordEvent("监听已连接");
        ensureKeepAliveIfNeeded();
        // 重新绑定后按当前条件恢复轮询，避免被系统解绑再绑定时轮询已停。
        syncPollingWithBluetooth();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.w(TAG, "onListenerDisconnected: rebinding.");
        recordEvent("监听已断开，2 秒后尝试重绑");
        // 系统解绑后（后台限制、进程降级等），主动请求重新绑定。
        if (!destroyed) {
            mainHandler.postDelayed(() -> {
                if (!destroyed) {
                    requestRebindIfNeeded(this);
                }
            }, 2000L);
        }
    }

    /**
     * 耳机已经连着但进程中途重启时，不会再有"蓝牙连接"广播，
     * 保活服务就永远不会被拉起。这里在服务启动/绑定成功后自检一次，
     * 把前台服务补回来，避免 App 退回没有保活的裸奔状态。
     */
    private void ensureKeepAliveIfNeeded() {
        boolean enabled = getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_ENABLED, true);
        if (!enabled) {
            // 播报被关掉后，服务重绑 / 进程重启都不该再把前台服务拉起来，
            // 否则状态栏会一直挂着「运行中」，用户以为关不掉。
            KeepAliveService.stop(this);
            return;
        }
        boolean connected = isBluetoothConnected();
        if (connected) {
            KeepAliveService.start(this);
            ensureTtsInitialized();
        }
    }

    /**
     * 蓝牙连接状态：优先用 audioDeviceCallback 维护的结果。
     * 早先缓存只有 1 秒，等于每个轮询 tick（5 秒）都要 getDevices() 跨进程一次，
     * 一天一万七千多次；而设备变化本来就有系统回调，没必要自己轮。
     */
    private boolean isBluetoothConnected() {
        long now = SystemClock.elapsedRealtime();
        if (bluetoothStateFromCallback && now - lastBluetoothCheckMs < BLUETOOTH_STATE_MAX_AGE_MS) {
            return lastBluetoothState;
        }
        if (now - lastBluetoothCheckMs < 5000L) {
            return lastBluetoothState;
        }
        lastBluetoothState = MainActivity.hasBluetoothAudioOutput(this);
        lastBluetoothCheckMs = now;
        return lastBluetoothState;
    }

    /** 设备增删时刷新一次状态，并标记"后续以回调为准"。 */
    private void refreshBluetoothStateFromCallback() {
        lastBluetoothState = MainActivity.hasBluetoothAudioOutput(this);
        bluetoothStateFromCallback = true;
        lastBluetoothCheckMs = SystemClock.elapsedRealtime();
    }

    /** 强制重查一次真实状态，不吃回调缓存（空闲看门狗和启停决策必须拿实况）。 */
    private boolean checkBluetoothFresh() {
        lastBluetoothState = MainActivity.hasBluetoothAudioOutput(this);
        lastBluetoothCheckMs = SystemClock.elapsedRealtime();
        return lastBluetoothState;
    }

    private void ensureTtsInitialized() {
        if (textToSpeech == null) {
            initializeTextToSpeech();
        }
    }

    /**
     * 勿扰时段：只在跨小时区间内静音播报，不影响队列以外的任何逻辑。
     */
    private boolean isInDoNotDisturbWindow() {
        SharedPreferences preferences =
                getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE);
        if (!preferences.getBoolean(MainActivity.KEY_DND_ENABLED, false)) {
            return false;
        }
        int start = preferences.getInt(MainActivity.KEY_DND_START, 23);
        int end = preferences.getInt(MainActivity.KEY_DND_END, 7);
        if (start == end) {
            return false;
        }
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (start < end) {
            return hour >= start && hour < end;
        }
        return hour >= start || hour < end;
    }

    private void applySpeechRate() {
        if (textToSpeech == null) {
            return;
        }
        int rate = getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .getInt(MainActivity.KEY_SPEECH_RATE, 100);
        float speechRate = Math.max(0.5f, Math.min(2.0f, rate / 100f));
        try {
            textToSpeech.setSpeechRate(speechRate);
        } catch (RuntimeException exception) {
            Log.w(TAG, "setSpeechRate failed", exception);
        }
    }

    /**
     * 设置页改完语速后立即生效。
     * 旧实现只在 TTS onInit 回调里 setSpeechRate 一次，用户拖动滑块后
     * 实际播报仍是旧速度，必须杀进程重启才生效。
     */
    static void applySpeechRateIfReady() {
        NotificationReaderService service = instance;
        if (service != null && service.ttsReady) {
            service.mainHandler.post(service::applySpeechRate);
        }
    }

    private void playBeepIfNeeded() {
        SharedPreferences preferences =
                getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE);
        if (!preferences.getBoolean(MainActivity.KEY_BEEP, true)) {
            return;
        }
        try {
            if (toneGenerator == null) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            }
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (RuntimeException exception) {
            Log.w(TAG, "beep failed", exception);
        }
    }

    /**
     * 条件不满足时清空轮询状态，等条件恢复后重新建基线，
     * 避免把停摆期间堆积的旧通知当成新通知念出来。
     */
    private void resetPollingState() {
        if (pollInitialized) {
            pollInitialized = false;
            lastFingerprints.clear();
            pendingAnnouncements.clear();
        }
    }

    static NotificationReaderService getInstance() {
        return instance;
    }

    /**
     * 厂商 ROM 在 App 进入后台后可能不再投递 onNotificationPosted 回调，
     * 但 getActiveNotifications() 主动查询往往仍然可用。
     * 由 KeepAliveService 定时调用这里，把"被动等推送"变成"主动拉取"。
     */
    void pollActiveNotifications() {
        // 门禁 1：播报已关闭 → 连通知都不读
        if (!getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_ENABLED, true)) {
            resetPollingState();
            return;
        }
        // 门禁 2：App 在前台时系统回调可靠，轮询是多余的
        if (MainActivity.isForeground) {
            return;
        }
        // 门禁 3：没连蓝牙耳机 → 完全不做任何事
        if (!isBluetoothConnected()) {
            resetPollingState();
            return;
        }
        ensureTtsInitialized();

        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException exception) {
            Log.w(TAG, "pollActiveNotifications failed", exception);
            return;
        }
        if (active == null) {
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - lastPollLogMs > 30_000L) {
            lastPollLogMs = nowMs;
            Log.i(TAG, "poll alive: " + active.length + " active, ttsReady=" + ttsReady
                    + ", initialized=" + pollInitialized);
        }
        if (!pollInitialized) {
            // 首次轮询只做基线记录，避免把通知栏里已有的历史通知全部念一遍。
            for (StatusBarNotification statusBarNotification : active) {
                lastFingerprints.put(statusBarNotification.getKey(),
                buildFingerprint(statusBarNotification));
                try {
                    String announcement = createAnnouncement(statusBarNotification);
                    if (announcement != null) {
                        recentNotifications.put(statusBarNotification.getKey(),
                                new SpokenNotification(announcement,
                                        SystemClock.elapsedRealtime(),
                                        statusBarNotification.getPostTime()));
                    }
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Baseline skip for " + statusBarNotification.getPackageName(),
                            exception);
                }
            }
            pollInitialized = true;
            Log.i(TAG, "Polling baseline: " + active.length + " notifications.");
            recordEvent("轮询基线建立：" + active.length + " 条已有通知不播报");
            return;
        }
        // 只处理"新出现"或"内容有更新"的通知。
        // 注意不能用 postTime 单独判断：微信/QQ 复用同一通知时 postTime 可能不变，
        // 但正文已经更新，只看时间会漏播，所以指纹里必须带上文本内容。
        Set<String> aliveKeys = new HashSet<>();
        for (StatusBarNotification statusBarNotification : active) {
            String key = statusBarNotification.getKey();
            aliveKeys.add(key);
            String fingerprint = buildFingerprint(statusBarNotification);
            String lastFingerprint = lastFingerprints.get(key);
            if (fingerprint != null && fingerprint.equals(lastFingerprint)) {
                continue;
            }
            try {
                recordArrival(statusBarNotification.getPackageName(), "轮询补获");
                handleNotificationPosted(statusBarNotification);
            } catch (RuntimeException exception) {
                Log.e(TAG, "Poll handling failed", exception);
            }        }
        lastFingerprints.keySet().retainAll(aliveKeys);
    }

    /**
     * 轻量指纹：只取时间戳和标题/正文原文，不做包名解析等跨进程操作。
     * 内容任何一处变化都会让指纹不同，从而不会漏播。
     */
    private String buildFingerprint(StatusBarNotification statusBarNotification) {
        Bundle extras = statusBarNotification.getNotification().extras;
        if (extras == null) {
            return String.valueOf(statusBarNotification.getPostTime());
        }
        String title = asString(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = asString(extras.getCharSequence(Notification.EXTRA_TEXT));
        String bigText = asString(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        return statusBarNotification.getPostTime() + "|" + title + "|" + text + "|" + bigText;
    }

    private String asString(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification statusBarNotification) {
        try {
            lastCallbackMs = SystemClock.elapsedRealtime();
            recordArrival(statusBarNotification.getPackageName(), "系统回调");
            handleNotificationPosted(statusBarNotification);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Failed to process a posted notification", exception);
        }
    }

    private synchronized void handleNotificationPosted(
            StatusBarNotification statusBarNotification) {
        String packageName = statusBarNotification.getPackageName();
        // 记录处理过的 postTime：轮询和系统回调共用，避免同一条通知被反复解析。
        lastFingerprints.put(statusBarNotification.getKey(),
                buildFingerprint(statusBarNotification));

        if (getPackageName().equals(packageName)) {
            return;
        }
        if (!getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_ENABLED, true)) {
            Log.d(TAG, "skip disabled: " + packageName);
            recordSkip(packageName, "总开关已关闭");
            return;
        }
        if (!isPackageAllowed(packageName)) {
            Log.d(TAG, "skip allowlist: " + packageName);
            recordSkip(packageName, "不在白名单");
            return;
        }
        if (isInDoNotDisturbWindow()) {
            Log.d(TAG, "skip dnd window: " + packageName);
            recordSkip(packageName, "处于勿扰时段");
            return;
        }

        Notification notification = statusBarNotification.getNotification();
        if ((notification.flags & (Notification.FLAG_ONGOING_EVENT
                | Notification.FLAG_GROUP_SUMMARY)) != 0) {
            Log.d(TAG, "skip ongoing/summary: " + packageName);
            recordSkip(packageName, "常驻通知或分组摘要");
            return;
        }

        if (!isBluetoothConnected()) {
            Log.d(TAG, "skip no bluetooth: " + packageName);
            recordSkip(packageName, "未连接蓝牙音频");
            pendingAnnouncements.clear();
            return;
        }

        String announcement = createAnnouncement(statusBarNotification);
        if (announcement == null) {
            Log.d(TAG, "skip empty text: " + packageName);
            recordSkip(packageName, "没有可读文字");
            return;
        }
        if (!isNewNotification(statusBarNotification, announcement)) {
            Log.d(TAG, "skip duplicate: " + packageName);
            recordSkip(packageName, "内容已播报过");
            return;
        }
        enqueueAnnouncement(statusBarNotification.getKey(), announcement);
        drainAnnouncementQueue();
    }

    /**
     * 入队规则：
     * 1. 同一会话（相同通知 key）只保留最新一条，避免连发时排队念旧消息
     * 2. 超过 30 秒的内容直接丢弃，不念过期消息
     * 3. 队列超过上限时挤掉最早的
     */
    private void enqueueAnnouncement(String key, String text) {
        long now = SystemClock.elapsedRealtime();
        pendingAnnouncements.removeIf(item -> item.key.equals(key));
        pendingAnnouncements.removeIf(item -> now - item.queuedAt > MAX_QUEUE_AGE_MS);
        while (pendingAnnouncements.size() >= MAX_PENDING_ANNOUNCEMENTS) {
            pendingAnnouncements.removeFirst();
        }
        pendingAnnouncements.addLast(new PendingAnnouncement(key, text, now));
        Log.i(TAG, "queued: " + text);
        recordEvent("入队播报：" + (text.length() > 40 ? text.substring(0, 40) + "…" : text));
    }

    /**
     * 白名单制：列表为空时播报所有应用；填入包名后只播报这些应用。
     * 解析结果按原文缓存：轮询每秒级触发，每条通知都 split+建 Set 是无谓开销。
     * 统一用 Locale.ROOT 做小写归一，避免土耳其语区 I→ı 的匹配异常。
     */
    private String cachedAllowRaw;
    private Set<String> cachedAllowSet;

    private boolean isPackageAllowed(String packageName) {
        String allowed = getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .getString(MainActivity.KEY_ALLOWED_PACKAGES, "");
        String raw = allowed == null ? "" : allowed.trim();
        if (raw.isEmpty()) {
            return true;
        }
        if (!raw.equals(cachedAllowRaw)) {
            Set<String> parsed = new HashSet<>();
            for (String entry : raw.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(trimmed.toLowerCase(java.util.Locale.ROOT));
                }
            }
            cachedAllowRaw = raw;
            cachedAllowSet = parsed;
        }
        return cachedAllowSet.contains(packageName.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 播报前临时把媒体音量提到用户设定的水平，播完恢复原值。
     * 只在目标音量高于当前音量时才提升，不会主动压低用户音量。
     */
    private void boostVolumeForSpeech() {
        if (audioManager == null || savedMusicVolume >= 0) {
            return;
        }
        SharedPreferences preferences =
                getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE);
        if (!preferences.getBoolean(MainActivity.KEY_BOOST_VOLUME, true)) {
            return;
        }
        int percent = preferences.getInt(MainActivity.KEY_VOLUME_PERCENT,
                MainActivity.DEFAULT_VOLUME_PERCENT);
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int target = Math.round(max * percent / 100f);
        if (target > current) {
            try {
                savedMusicVolume = current;
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                mainHandler.removeCallbacks(volumeSafetyRestore);
                mainHandler.postDelayed(volumeSafetyRestore, VOLUME_SAFETY_TIMEOUT_MS);
                Log.i(TAG, "volume boosted " + current + " -> " + target + " (max " + max + ")");
            } catch (RuntimeException exception) {
                savedMusicVolume = -1;
                Log.w(TAG, "volume boost failed", exception);
            }
        }
    }

    private void restoreVolumeAfterSpeech() {
        mainHandler.removeCallbacks(volumeSafetyRestore);
        if (savedMusicVolume >= 0 && audioManager != null) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0);
                Log.i(TAG, "volume restored to " + savedMusicVolume);
            } catch (RuntimeException exception) {
                Log.w(TAG, "volume restore failed", exception);
            }
            savedMusicVolume = -1;
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification statusBarNotification) {
        recentNotifications.remove(statusBarNotification.getKey());
        lastFingerprints.remove(statusBarNotification.getKey());
    }

    /**
     * 应用名缓存：避免每条通知都做一次跨进程查询。
     */
    private String resolveAppLabel(String packageName) {
        String cached = appLabelCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        String label = packageName;
        try {
            label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception exception) {
            Log.w(TAG, "Could not resolve app label for " + packageName);
        }
        appLabelCache.put(packageName, label);
        return label;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy.");
        recordEvent("监听服务已销毁");
        destroyed = true;
        pollHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        if (instance == this) {
            instance = null;
        }
        pendingAnnouncements.clear();
        abandonAudioFocus();
        restoreVolumeAfterSpeech();
        if (audioManager != null) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        if (toneGenerator != null) {
            try {
                toneGenerator.release();
            } catch (RuntimeException ignored) {
            }
            toneGenerator = null;
        }
        super.onDestroy();
    }

    private void initializeTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed, status=" + status);
                recordEvent("TTS 初始化失败 (status=" + status + ")");
                // 必须把实例置空，否则 ensureTtsInitialized() 认为引擎还在，
                // 整个进程生命周期内一条都念不出来（只能杀进程恢复）。
                TextToSpeech broken = textToSpeech;
                textToSpeech = null;
                ttsReady = false;
                if (broken != null) {
                    try {
                        broken.shutdown();
                    } catch (RuntimeException ignored) {
                    }
                }
                if (!destroyed && ttsInitRetries < MAX_TTS_INIT_RETRIES) {
                    ttsInitRetries++;
                    mainHandler.postDelayed(this::initializeTextToSpeech, 8000L);
                }
                return;
            }
            ttsInitRetries = 0;
            textToSpeech.setAudioAttributes(speechAudioAttributes());
            ttsReady = true;
            applySpeechRate();
            Log.i(TAG, "TTS ready.");
            recordEvent("TTS 引擎已就绪");
            drainAnnouncementQueue();
        });
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.i(TAG, "TTS onStart " + utteranceId);
                // 守卫改从「真正开播」计时：QUEUE_ADD 排队的第 3、4 条如果按
                // speak() 调用时刻算，60 秒守卫会在它们念完前误触发，
                // 提前放弃焦点并把音量降回去，听感就是「声音忽大忽小」。
                mainHandler.removeCallbacks(utteranceTimeoutGuard);
                mainHandler.postDelayed(utteranceTimeoutGuard, UTTERANCE_TIMEOUT_MS);
            }

            @Override
            public void onDone(String utteranceId) {
                Log.i(TAG, "TTS onDone " + utteranceId);
                mainHandler.post(() -> onUtteranceFinished(utteranceId));
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "TTS onError " + utteranceId);
                mainHandler.post(() -> onUtteranceFinished(utteranceId));
            }

            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                Log.i(TAG, "TTS onStop " + utteranceId + " interrupted=" + interrupted);
                mainHandler.post(() -> onUtteranceFinished(utteranceId));
            }
        });
    }

    private String createAnnouncement(StatusBarNotification statusBarNotification) {
        Bundle extras = statusBarNotification.getNotification().extras;
        if (extras == null) {
            return null;
        }

        String title = toText(extras.getCharSequence(Notification.EXTRA_TITLE));
        String body = toText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (body.isEmpty()) {
            body = toText(extras.getCharSequence(Notification.EXTRA_TEXT));
        }
        if (body.isEmpty()) {
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) {
                body = toText(lines[lines.length - 1]);
            }
        }
        if (title.isEmpty() && body.isEmpty()) {
            return null;
        }

        String appName = resolveAppLabel(statusBarNotification.getPackageName());
        SharedPreferences preferences =
                getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE);
        String composed = TextFormatter.compose(
                appName,
                TextFormatter.cleanText(title),
                body,
                preferences.getBoolean(MainActivity.KEY_READ_APP_NAME, true),
                preferences.getBoolean(MainActivity.KEY_READ_TITLE, true),
                preferences.getBoolean(MainActivity.KEY_READ_BODY, true),
                MAX_BODY_LENGTH);
        return composed.isEmpty() ? null : composed;
    }

    private synchronized boolean isNewNotification(StatusBarNotification notification,
            String announcement) {
        long postTime = notification.getPostTime();
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, SpokenNotification>> iterator =
                recentNotifications.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().timestamp > DUPLICATE_WINDOW_MS) {
                iterator.remove();
            }
        }

        String key = notification.getKey();
        SpokenNotification previous = recentNotifications.get(key);
        // 文本相同还不够：同一个人两分钟内连发两条「好的」，第二条 postTime 一定
        // 前进；只有 postTime 没有前进（IM 复用同一通知刷新内容）才算重复。
        // 早先只用「文本 + 120 秒窗口」判断，会把这些重复消息整条吞掉。
        if (previous != null && previous.announcement.equals(announcement)
                && postTime <= previous.postTime
                && now - previous.timestamp <= DUPLICATE_WINDOW_MS) {
            return false;
        }
        recentNotifications.put(key, new SpokenNotification(announcement, now, postTime));
        return true;
    }

    /**
     * 用户关掉播报（App 内开关或快捷磁贴）时必须立刻停下。
     * 只写 SharedPreferences 是不够的：队列里最多还压着 5 条，引擎里还有已排队
     * 的 utterance，它们会继续念几十秒，表现就是「关了还在念」。
     */
    static void stopEverything(Context context) {
        NotificationReaderService service = instance;
        if (service == null) {
            return;
        }
        service.mainHandler.post(() -> {
            if (service.pendingAnnouncements.isEmpty() && service.activeUtterances.isEmpty()
                    && service.savedMusicVolume < 0) {
                // 已经在关着的空档：onResume 每次都调过来，别反复 stop 引擎刷日志
                return;
            }
            service.pendingAnnouncements.clear();
            service.activeUtterances.clear();
            if (service.textToSpeech != null) {
                service.textToSpeech.stop();
            }
            service.abandonAudioFocus();
            service.restoreVolumeAfterSpeech();
            recordEvent("播报已关闭，队列与引擎队列一并清空");
        });
    }

    private void drainAnnouncementQueue() {
        if (pendingAnnouncements.isEmpty()) {
            return;
        }
        // 兜底：任何延迟重试（焦点、朗读失败）都不该在开关关闭后再把内容念出来
        if (!getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_ENABLED, true)) {
            Log.i(TAG, "drain: announcements disabled, dropping queue.");
            pendingAnnouncements.clear();
            return;
        }
        // 用 1 秒缓存的蓝牙状态即可：hasBluetoothAudioOutput 每次都跨 binder 查
        // AudioManager，而这里的判定不依赖亚秒级精度。
        if (!isBluetoothConnected()) {
            Log.i(TAG, "drain: no bluetooth audio output, dropping queue.");
            pendingAnnouncements.clear();
            return;
        }
        if (!ttsReady) {
            Log.i(TAG, "drain: TTS not ready yet, keeping queue.");
            return;
        }
        while (!pendingAnnouncements.isEmpty()) {
            PendingAnnouncement item = pendingAnnouncements.removeFirst();
            if (SystemClock.elapsedRealtime() - item.queuedAt > MAX_QUEUE_AGE_MS) {
                Log.i(TAG, "drop stale announcement");
                continue;
            }
            if (!speak(item)) {
                // 播报没发出去（音频焦点被占用），speak 已把这条放回队首，
                // 必须停下这轮循环，否则 while 会反复取到同一条而死循环。
                break;
            }
        }
    }

    private boolean speak(PendingAnnouncement item) {
        String announcement = item.text;
        if (textToSpeech == null) {
            Log.e(TAG, "speak: TTS instance is null.");
            return false;
        }
        boolean firstInBatch = activeUtterances.isEmpty();
        if (firstInBatch && !requestAudioFocus()) {
            Log.w(TAG, "speak: could not obtain audio focus.");
            recordEvent("播报被跳过：音频焦点被其他应用占用，稍后重试");
            // 没念出来的内容不能算「已播报」：isNewNotification 已经把这条
            // 标成最新内容，不回队的话这条通知就永久丢了。
            if (SystemClock.elapsedRealtime() - item.queuedAt <= MAX_QUEUE_AGE_MS) {
                item.retried = true;
                pendingAnnouncements.addFirst(item);
                mainHandler.postDelayed(this::drainAnnouncementQueue, 3000L);
            }
            return false;
        }
        if (firstInBatch) {
            boostVolumeForSpeech();
            if (!item.retried) {
                playBeepIfNeeded();
            }
        }

        String utteranceId = UUID.randomUUID().toString();
        // 先登记再 speak：极短的语音可能在回调前就结束，顺序反过来会漏记
        activeUtterances.add(utteranceId);
        int result = textToSpeech.speak(
                announcement, TextToSpeech.QUEUE_ADD, new Bundle(), utteranceId);
        Log.i(TAG, "speak result=" + result + " text=" + announcement);
        recordEvent(result == TextToSpeech.SUCCESS ? "开始朗读" : "朗读失败 (code=" + result + ")");
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "speak: TTS rejected the utterance.");
            activeUtterances.remove(utteranceId);
            if (activeUtterances.isEmpty()) {
                abandonAudioFocus();
                restoreVolumeAfterSpeech();
            }
            // 引擎瞬时繁忙也会返回 ERROR：不回队重试就等于永久丢了这条
            if (SystemClock.elapsedRealtime() - item.queuedAt <= MAX_QUEUE_AGE_MS) {
                item.retried = true;
                pendingAnnouncements.addFirst(item);
                mainHandler.postDelayed(this::drainAnnouncementQueue, 4000L);
            }
            return false;
        }
        mainHandler.postDelayed(utteranceTimeoutGuard, UTTERANCE_TIMEOUT_MS);
        return true;
    }

    private void onUtteranceTimeoutGuard() {
        if (!activeUtterances.isEmpty()) {
            Log.w(TAG, "Utterance timeout guard fired; clearing " + activeUtterances.size());
            onUtteranceFinished(null);
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) {
            Log.e(TAG, "requestAudioFocus: AudioManager is null.");
            return false;
        }
        audioFocusRequest = new AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(speechAudioAttributes())
                .setOnAudioFocusChangeListener(focusChange -> {
                    // 空实现会让 TTS 压在对方语音上继续念（来电、导航、音乐）
                    mainHandler.post(() -> onAudioFocusLost(focusChange));
                })
                .build();
        boolean granted = audioManager.requestAudioFocus(audioFocusRequest)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        Log.i(TAG, "requestAudioFocus granted=" + granted);
        return granted;
    }

    private void onUtteranceFinished(String utteranceId) {
        if (utteranceId == null) {
            // 超时守卫：不再信任回调，直接清空
            if (activeUtterances.isEmpty()) {
                return;
            }
            activeUtterances.clear();
        } else if (!activeUtterances.remove(utteranceId)) {
            // 同一条语音的重复回调（有些引擎同时发 onDone 和 onStop），忽略
            Log.i(TAG, "duplicate TTS finish callback " + utteranceId + ", ignored.");
            return;
        }
        if (activeUtterances.isEmpty()) {
            // 不撤的话，上一批残留的守卫会在下一批刚 speak、还没 onStart 的
            // 空隙里误触发，把新批次连焦点带音量一起清掉
            mainHandler.removeCallbacks(utteranceTimeoutGuard);
            abandonAudioFocus();
            restoreVolumeAfterSpeech();
        }
    }

    /**
     * 焦点被抢时必须停嘴，但要不要丢积压得分情况：
     * LOSS（对方长期持有，例如音乐开始播放）短期回不来，停播并丢弃积压；
     * TRANSIENT / CAN_DUCK（导航一句、来电铃声）只是短抢占，队列必须留着稍后重试，
     * 否则一句导航语音就能把整批消息永久吞掉——那些条目早已被标记「已播报」。
     */
    private void onAudioFocusLost(int focusChange) {
        if (activeUtterances.isEmpty() && pendingAnnouncements.isEmpty()) {
            return;
        }
        boolean permanent = focusChange == AudioManager.AUDIOFOCUS_LOSS;
        Log.i(TAG, "audio focus lost, focusChange=" + focusChange
                + " permanent=" + permanent);
        if (permanent) {
            pendingAnnouncements.clear();
            recordEvent("焦点被长期占用，已停止播报并丢弃积压");
        } else {
            recordEvent("焦点被短暂抢占，已暂停，稍后继续");
        }
        activeUtterances.clear();
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        abandonAudioFocus();
        restoreVolumeAfterSpeech();
        if (!permanent) {
            mainHandler.postDelayed(this::drainAnnouncementQueue, 5000L);
        }
    }

    private void abandonAudioFocus() {
        if (audioFocusRequest != null && audioManager != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        }
    }

    private AudioAttributes speechAudioAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    private String toText(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static final class PendingAnnouncement {
        final String key;
        final String text;
        final long queuedAt;
        /** 重试过的条目不再放提示音，免得引擎繁忙时每 4 秒「嘀」一次。 */
        boolean retried;

        PendingAnnouncement(String key, String text, long queuedAt) {
            this.key = key;
            this.text = text;
            this.queuedAt = queuedAt;
        }
    }

    private static final class SpokenNotification {
        final String announcement;
        final long timestamp;
        final long postTime;

        SpokenNotification(String announcement, long timestamp, long postTime) {
            this.announcement = announcement;
            this.timestamp = timestamp;
            this.postTime = postTime;
        }
    }
}
