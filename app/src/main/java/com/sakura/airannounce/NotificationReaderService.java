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
    /**
     * 正文朗读上限。300 字对"听完一条通知"来说太长：一条长群消息能念叨十几秒，
     * 后面排队的消息全部被拖延迟；配合逐条投递后重播代价也更小。
     */
    private static final int MAX_BODY_LENGTH = 120;
    /** 队列里超过这个年龄的内容直接丢弃，避免念出几分钟前的旧消息。 */
    private static final long MAX_QUEUE_AGE_MS = 30_000L;
    /** 单条 utterance 守卫的封顶值。实际时长按字数估算，见 SPEECH_GUARD_BASE_MS。 */
    private static final long UTTERANCE_TIMEOUT_MS = 60_000L;
    /**
     * 逐条投递后每条 utterance 都要单独计时，固定 60 秒对短播报太宽松、
     * 对 120 字长播报又不够用（4 字/秒就要 30 秒，再叠加引擎起播延迟）。
     * 改成「15 秒起步 + 每字 0.4 秒」，封顶 UTTERANCE_TIMEOUT_MS。
     */
    private static final long SPEECH_GUARD_BASE_MS = 15_000L;
    private static final long SPEECH_GUARD_PER_CHAR_MS = 400L;

    /** 自适应轮询阶梯：连续零补获时逐级退避；第 0 档等于用户档位与 5 秒中较慢的那个。 */
    private static final long[] POLL_LADDER_MS = {5_000L, 15_000L, 30_000L, 60_000L};
    private static final String[] POLL_TIER_NAMES = {"5 秒", "15 秒", "30 秒", "60 秒"};
    /**
     * 进入下一档所需的连续「零补获」轮询次数。按最快档 5 秒折算：
     * 12 次 ≈ 1 分钟、30 次 ≈ 4 分钟、60 次 ≈ 10 分钟。
     */
    private static final int[] POLL_LADDER_STEPS = {12, 30, 60};
    /**
     * 补获年龄门：轮询看到某条通知时，它至少已经存在这么久，才算「系统回调漏投」。
     * 回调被主线程堵住几百毫秒、随后才到，属于竞态而不是故障；
     * 少了这道门，一次假补获就能把阶梯打回最快档。
     */
    private static final long POLL_CATCH_MIN_AGE_MS = 2_000L;

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
                    // 档位变了就留一条记录，方便事后对上「那段时间为什么省电 / 为什么播报变慢」。
                    if (isAdaptivePollEnabled()) {
                        int tier = pollTier();
                        if (tier != lastRecordedTier) {
                            lastRecordedTier = tier;
                            recordEvent("轮询档位 → " + POLL_TIER_NAMES[tier]
                                    + "（连续零补获 " + emptyPollStreak + " 次）");
                        }
                    }
                    pollHandler.postDelayed(this, pollInterval());
                }
            }
        }
    };

    /**
     * 轮询间隔可在设置里调整，默认 5 秒（1–30 秒可选）。
     *
     * 判据的演进（改动动机就写在这，免得以后又被"看回调时间戳"这种直觉骗回去）：
     * 旧实现拿「最近 2 分钟有没有收到系统回调」当通道健康度。可回调只在有通知时才来，
     * 安静时段时间戳必然陈旧，于是被判成"通道可疑"，整夜 5 秒一轮——
     * 恰恰是没事发生、最该省电的时候最费电。
     * 现在改用「这一轮到底有没有补到东西」：补到说明回调漏投，立刻回最快档；
     * 连续补不到说明只是没事发生，逐级退避。沉默不再被当成故障证据。
     */
    private long pollInterval() {
        int value = getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .getInt(MainActivity.KEY_POLL_INTERVAL, MainActivity.DEFAULT_POLL_INTERVAL_MS);
        if (value < 1000) {
            value = 1000;
        }
        if (value > 30000) {
            value = 30000;
        }
        if (!isAdaptivePollEnabled()) {
            // 自适应关掉：严格照用户档位，设置值就是最终值，不做静默改写。
            return value;
        }
        if (!listenerBound) {
            // 硬信号：监听器没绑上，回调一定收不到，轮询是唯一通道，按允许的最快档跑。
            return Math.min(value, POLL_LADDER_MS[0]);
        }
        return Math.max(value, POLL_LADDER_MS[pollTier()]);
    }

    /** 供 KeepAliveService 判断绑定状态；不再用 getInstance()==null 这种近似。 */
    static boolean isListenerBound() {
        return listenerBound;
    }

    /** 当前退避档位：0=最快，3=最慢。 */
    private int pollTier() {
        if (emptyPollStreak < POLL_LADDER_STEPS[0]) {
            return 0;
        }
        if (emptyPollStreak < POLL_LADDER_STEPS[1]) {
            return 1;
        }
        if (emptyPollStreak < POLL_LADDER_STEPS[2]) {
            return 2;
        }
        // 60 秒档只给灭屏：亮屏说明人可能正盯着通知，
        // 真漏投时让消息晚一分钟到，比省这点电代价大。
        return isScreenOff() ? 3 : 2;
    }

    private boolean isAdaptivePollEnabled() {
        return getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_ADAPTIVE_POLL, true);
    }

    private boolean isScreenOff() {
        android.os.PowerManager powerManager =
                (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return powerManager != null && !powerManager.isInteractive();
    }

    /** 一行说明当前间隔的来历，给「为什么不播 / 为什么费电」这类问题用。 */
    private String describePollStrategy() {
        if (!isAdaptivePollEnabled()) {
            return "固定档位";
        }
        if (!listenerBound) {
            return "监听未连接，强制最快档";
        }
        if (emptyPollStreak < POLL_LADDER_STEPS[0]) {
            return "刚补获过或刚起轮，最快档";
        }
        return "连续零补获 " + emptyPollStreak + " 次，第 " + (pollTier() + 1) + " 档";
    }

    /** 设置页改完档位后立刻按新间隔重排下一次 tick，不用等当前这一轮跑完。 */
    static void refreshPollCadence() {
        NotificationReaderService service = instance;
        if (service == null || service.destroyed) {
            return;
        }
        service.pollHandler.post(() -> {
            if (service.destroyed || !service.pollingActive) {
                return;
            }
            service.lastRecordedTier = -1;
            service.pollHandler.removeCallbacks(service.pollRunnable);
            service.pollHandler.postDelayed(service.pollRunnable, service.pollInterval());
        });
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
        // 退避状态一起复位：耳机断开期间没有任何判据在跑，
        // 留着旧计数会让重新连上后的头几轮还挂在慢档上。
        emptyPollStreak = 0;
        lastRecordedTier = -1;
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
    /**
     * 播报状态机。
     *
     * 原来这套逻辑是 11 条补丁叠在 speaking / paused / currentItem / guard / volume /
     * focus 六个独立字段上：任何一个出口忘了置位，结果就是「音频焦点和抬高后的音量
     * 一直挂着」。收成枚举之后所有出口都必须走 endSpeechSession()，漏一处会明显不一致。
     */
    private enum SpeechPhase { IDLE, SPEAKING, PAUSED }
    private SpeechPhase speechPhase = SpeechPhase.IDLE;
    /** 引擎正在念的那一条的 token；null 表示没有在念。对不上的回调一律丢弃。 */
    private String activeToken;
    /** 会话序号与条目序号：让被冻结期间排队、解冻后集中爆发的旧回调自然失效。 */
    private long sessionSeq;
    private long itemSeq;
    /** 连续 speak() 失败次数：连续失败意味着引擎坏了，要重建而不是无限重试。 */
    private int speakFailStreak;
    /** 引擎代数：每次重建 +1，写进 token，让旧引擎迟到的回调一定会对不上。 */
    private int engineEpoch;
    private static final int MAX_SPEAK_RETRIES = 2;
    /**
     * 连续失败到这个数就重建引擎。必须小于「重试上限 + 1」，否则重建总是发生在
     * 该条已经被放弃之后，那条通知永远享受不到新引擎（上限 2 = 每条 3 次机会，
     * 阈值取 2 才能让最后一次尝试落在新引擎上）。
     */
    private static final int MAX_SPEAK_FAIL_STREAK = 2;
    /** 启动守卫：speak() 返回 SUCCESS 但引擎迟迟不开口时最多等这么久。 */
    private static final long START_GUARD_MS = 5_000L;
    /** onDone 之后等 A2DP 管线把尾音放完再还原音量，否则最后半句会被削掉。 */
    private static final long VOLUME_RESTORE_DELAY_MS = 400L;
    /**
     * 正在念的那一条。焦点被短暂抢占时 textToSpeech.stop() 会把它掐断，
     * 而它在 isNewNotification 里已被标记「已播报」——不回队就是永久丢失。
     */
    private PendingAnnouncement currentItem;
    /** 守卫拆两段：开口前的启动守卫 + 开口后按字数估的完成守卫。 */
    private final Runnable startGuard = this::onStartGuardTimeout;
    private final Runnable finishGuard = this::onFinishGuardTimeout;
    /** 本次会话提升到的目标音量；-1 表示没提升过。落盘续租要用它。 */
    private int boostedMusicVolume = -1;
    /** 守卫的计划触发时刻：实际触发晚太多说明线程被冻过，是假超时。 */
    private long startGuardDueMs;
    private long finishGuardDueMs;
    /** 假超时只宽限一次，否则真卡死时会一直宽限下去。 */
    private boolean startGuardGraced;
    private boolean finishGuardGraced;
    /** 引擎重建的窗口与次数：反复坏掉时不能无限重建。 */
    private long rebuildWindowStartMs;
    private int rebuildCount;
    private static final int MAX_REBUILD_PER_WINDOW = 6;
    private static final long REBUILD_WINDOW_MS = 3600_000L;
    /**
     * 音量还原的延迟执行体。
     * 迟到执行时若已经有新会话在念，必须放手：否则它会把新会话刚提上去的音量
     * 和刚写好的落盘记录一起抹掉。
     */
    private final Runnable pendingVolumeRestore = () -> {
        if (speechPhase == SpeechPhase.SPEAKING) {
            return;
        }
        restoreVolumeAfterSpeech();
    };
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

    /**
     * 本进程此刻是否处在「播报会话」里：保活服务据此判断「能不能动音量」。
     *
     * 注意 PAUSED 也算：暂停时音量故意保持在提升值上（否则恢复续播会掉一下声音），
     * 这时候把它当成「没在播报」去还原，就会在来电结束后以原音量开口。
     */
    static boolean isSpeakingNow() {
        NotificationReaderService service = instance;
        return service != null && service.speechPhase != SpeechPhase.IDLE;
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
                .append(" 条，播放中：")
                .append(service.speechPhase == SpeechPhase.SPEAKING ? 1 : 0).append(" 条\n");
        long sinceTick = service.lastPollTickMs <= 0L
                ? -1L
                : SystemClock.elapsedRealtime() - service.lastPollTickMs;
        builder.append("· 轮询：").append(service.pollingActive
                        ? ("运行中，间隔 " + (service.pollInterval() / 1000f) + " 秒（"
                                + service.describePollStrategy() + "）")
                        : "已暂停（未连接耳机）")
                .append(sinceTick < 0 ? "，尚未跑过" : "，上次 tick " + (sinceTick / 1000L) + " 秒前")
                .append('\n');
        if (service.pollingActive) {
            builder.append("· 补获：连续 ").append(service.emptyPollStreak)
                    .append(" 次轮询没补到东西（满 ").append(POLL_LADDER_STEPS[0])
                    .append(" 次退 ").append(POLL_TIER_NAMES[1])
                    .append("，满 ").append(POLL_LADDER_STEPS[1]).append(" 次退 ")
                    .append(POLL_TIER_NAMES[2]).append("，满 ").append(POLL_LADDER_STEPS[2])
                    .append(" 次且灭屏退 ").append(POLL_TIER_NAMES[3]).append("）\n");
        }
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
    /**
     * 监听器当前是否已绑定：绑不上就是「回调一定收不到」的硬信号。
     * static 是因为 KeepAliveService 要在「服务实例可能为 null」的时候判断它——
     * 用 getInstance()==null 当代替品会漏掉「对象还在、绑定已断」的状态（实测盲窗 5 分钟）。
     */
    private static volatile boolean listenerBound;
    /** 连续「零补获」的轮询次数，自适应退避的唯一判据。 */
    private int emptyPollStreak;
    /** 上次记录的档位，只在变化时写事件，避免把最近处理记录刷满。 */
    private int lastRecordedTier = -1;
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
                activeToken = null;
                currentItem = null;
                speechPhase = SpeechPhase.IDLE;
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

    /**
     * requestRebind 是"请求"，不是"保证"：系统可能因为解绑中的竞态、进程刚重启、
     * 或者 binder 侧状态不一致而直接忽略它，且没有任何回调告诉我们失败了。
     * 之前的实现发一次就结束，于是出现"权限还在、服务没绑、界面显示正常，
     * 但一条都不播报"的状态，只能等人手动开一下 App。这里补上复核与退避重试。
     */
    private static final long[] REBIND_RETRY_DELAYS_MS = {3_000L, 10_000L, 30_000L};
    /**
     * 重试链只允许存在一条。
     * WakeReceiver、KeepAliveService.onCreate、蓝牙广播几乎会在同一秒内各自调一次
     * requestRebindIfNeeded，不去重的话就是三条链并行发 requestRebind，
     * 日志里会看到同一个 attempt 重复三次。
     */
    private static volatile boolean rebindChainRunning;

    static void requestRebindIfNeeded(Context context) {
        if (rebindChainRunning) {
            return;
        }
        rebindChainRunning = true;
        rebindAttempt(context.getApplicationContext(), 0);
    }

    private static void rebindAttempt(Context context, int attempt) {
        if (instance != null) {
            // 已经绑上了（可能是上一次请求生效了），不用再折腾
            rebindChainRunning = false;
            return;
        }
        try {
            android.content.ComponentName component =
                    new android.content.ComponentName(context, NotificationReaderService.class);
            NotificationListenerService.requestRebind(component);
            Log.i(TAG, "requestRebind issued (attempt " + (attempt + 1) + ").");
        } catch (RuntimeException exception) {
            Log.w(TAG, "requestRebind failed", exception);
        }
        if (attempt >= REBIND_RETRY_DELAYS_MS.length) {
            recordEvent("监听重绑多次未生效，可能需要手动重新授权");
            rebindChainRunning = false;
            return;
        }
        final Context appContext = context;
        final int next = attempt + 1;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> {
                    if (instance == null) {
                        Log.w(TAG, "listener still unbound after " + REBIND_RETRY_DELAYS_MS[attempt]
                                + "ms, retrying.");
                        rebindAttempt(appContext, next);
                    }
                },
                REBIND_RETRY_DELAYS_MS[attempt]);
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
        rebindChainRunning = false;
        // 绑定成功就把"监听掉线"的提醒和计时一并清掉，别让旧通知留在通知栏里
        ListenerAlert.cancel(this);
        getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .remove("listener_down_since_ms")
                .apply();
        recordEvent("监听已连接");
        // 绑定成功是「回调通道通」的硬证据：补获计数清零，回到最快档。
        listenerBound = true;
        emptyPollStreak = 0;
        lastRecordedTier = -1;
        // 上一次切换绑定的窗口标记到这里才算真正收尾：权限已在，标记只是没删干净。
        ListenerHeal.clearInflight(this);
        // 切换窗口里到达的通知既不进回调（那时没绑定）也可能已被轮询跳过，
        // 这里补一次即时的：syncPollingWithBluetooth 会把轮询拉回 1 秒档并立刻跑一轮。
        // 顺手把「上次提了音量没还原」的残留收掉 —— 进程刚起来时这件事最该做。
        SpeechVolumeGuard.recoverIfNeeded(this, false);
        ensureKeepAliveIfNeeded();
        // 重新绑定后按当前条件恢复轮询，避免被系统解绑再绑定时轮询已停。
        syncPollingWithBluetooth();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.w(TAG, "onListenerDisconnected: rebinding.");
        recordEvent("监听已断开，2 秒后尝试重绑");
        // 绑定断了是硬故障：回调一定不会来，补获计数清零并立刻切最快档，
        // 不能等「12 次零补获」的自然退避规则再走一遍。
        listenerBound = false;
        emptyPollStreak = 0;
        lastRecordedTier = -1;
        if (!destroyed) {
            startPolling(1000L);
        }
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
        boolean caughtByPoll = false;
        // 年龄门用墙上时钟：postTime 是 System.currentTimeMillis()，
        // 跟 elapsedRealtime() 不是同一基准，两个相减会得出荒谬的年龄。
        long nowWallMs = System.currentTimeMillis();
        for (StatusBarNotification statusBarNotification : active) {
            String key = statusBarNotification.getKey();
            aliveKeys.add(key);
            String fingerprint = buildFingerprint(statusBarNotification);
            String lastFingerprint = lastFingerprints.get(key);
            if (fingerprint != null && fingerprint.equals(lastFingerprint)) {
                continue;
            }
            if (nowWallMs - statusBarNotification.getPostTime() >= POLL_CATCH_MIN_AGE_MS) {
                caughtByPoll = true;
            }
            try {
                recordArrival(statusBarNotification.getPackageName(), "轮询补获");
                handleNotificationPosted(statusBarNotification);
            } catch (RuntimeException exception) {
                Log.e(TAG, "Poll handling failed", exception);
            }
        }
        // 只有这一个判据：这一轮有没有补到「本该由回调送来」的东西。
        // 补到了 → 回调不可靠，立刻回最快档；没补到 → 只是没事发生，继续累计退避。
        if (caughtByPoll) {
            if (emptyPollStreak >= POLL_LADDER_STEPS[0]) {
                recordEvent("轮询补获漏投内容，退避复位（此前连续零补获 " + emptyPollStreak + " 次）");
            }
            emptyPollStreak = 0;
        } else {
            emptyPollStreak = Math.min(emptyPollStreak + 1, 10_000);
        }
        lastFingerprints.keySet().retainAll(aliveKeys);
    }

    /**
     * 轻量指纹：时间戳 + 三段文本的 32 位哈希，不做包名解析等跨进程操作。
     * 内容任何一处变化都会让指纹不同，从而不会漏播。
     *
     * 存哈希而不是原文：这个函数每个 tick 要对所有活动通知跑一遍，
     * handleNotificationPosted 里还会再算一次；拼长字符串的分配开销在
     * 进度条类通知（每秒刷新）上最明显。三段各取一个 32 位哈希再加 postTime，
     * 误判概率可以忽略，而分配量从"整段正文"降到"几个短串"。
     */
    private String buildFingerprint(StatusBarNotification statusBarNotification) {
        Bundle extras = statusBarNotification.getNotification().extras;
        if (extras == null) {
            return String.valueOf(statusBarNotification.getPostTime());
        }
        return statusBarNotification.getPostTime()
                + "|" + hashOf(extras.getCharSequence(Notification.EXTRA_TITLE))
                + "|" + hashOf(extras.getCharSequence(Notification.EXTRA_TEXT))
                + "|" + hashOf(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
    }

    /**
     * 必须先 toString() 再 hashCode()：extras 里常见 SpannableString，
     * 它的 hashCode 继承自 Object（按实例算），直接取会让同一条内容每轮都被判成"变了"，
     * 那就变成了每次轮询都重复播报。
     */
    private String hashOf(CharSequence value) {
        if (value == null) {
            return "-";
        }
        return Integer.toHexString(value.toString().hashCode());
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

    /**
     * 跳过日志的节流。有些应用（代理工具、下载器）把通知当仪表盘，每秒更新一次，
     * 于是 onNotificationPosted 每秒被系统叫一次；日志不节流的话 logcat 环形缓冲
     * 会在几十分钟内被同一行刷满，真正的故障现场反而被冲掉。
     * recordSkip 早就有同样的 60 秒节流，这里补的是日志侧。
     */
    private static final long SKIP_LOG_INTERVAL_MS = 60_000L;
    private final Map<String, Long> lastSkipLogMs = new HashMap<>();

    private void logSkip(String reason, String packageName) {
        String key = reason + '|' + packageName;
        long now = SystemClock.elapsedRealtime();
        Long last = lastSkipLogMs.get(key);
        if (last != null && now - last < SKIP_LOG_INTERVAL_MS) {
            return;
        }
        if (lastSkipLogMs.size() > 200) {
            lastSkipLogMs.clear();
        }
        lastSkipLogMs.put(key, now);
        Log.d(TAG, "skip " + reason + ": " + packageName);
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
            logSkip("disabled", packageName);
            recordSkip(packageName, "总开关已关闭");
            return;
        }
        if (!isPackageAllowed(packageName)) {
            logSkip("allowlist", packageName);
            recordSkip(packageName, "不在白名单");
            return;
        }
        if (isInDoNotDisturbWindow()) {
            logSkip("dnd", packageName);
            recordSkip(packageName, "处于勿扰时段");
            return;
        }

        Notification notification = statusBarNotification.getNotification();
        if ((notification.flags & (Notification.FLAG_ONGOING_EVENT
                | Notification.FLAG_GROUP_SUMMARY)) != 0) {
            logSkip("ongoing", packageName);
            recordSkip(packageName, "常驻通知或分组摘要");
            return;
        }

        if (!isBluetoothConnected()) {
            logSkip("no-bluetooth", packageName);
            recordSkip(packageName, "未连接蓝牙音频");
            pendingAnnouncements.clear();
            return;
        }

        String announcement = createAnnouncement(statusBarNotification);
        if (announcement == null) {
            logSkip("empty-text", packageName);
            recordSkip(packageName, "没有可读文字");
            return;
        }
        if (!isNewNotification(statusBarNotification, announcement)) {
            logSkip("duplicate", packageName);
            recordSkip(packageName, "内容已播报过");
            return;
        }
        enqueueAnnouncement(statusBarNotification.getKey(), announcement,
                statusBarNotification.getPostTime());
        drainAnnouncementQueue();
    }

    /**
     * 入队规则：
     * 1. 同一会话（相同通知 key）只保留最新一条，避免连发时排队念旧消息
     * 2. 超过 30 秒的内容直接丢弃，不念过期消息
     * 3. 队列超过上限时挤掉最早的
     */
    /**
     * 入队。
     *
     * 这个方法由 onNotificationPosted 在 binder 线程调用，而队列的消费方
     * （drain / 收尾 / 焦点回调）全都在 mainHandler 上。两个线程直接改同一个
     * ArrayDeque 会丢条目，遍历时还可能抛 ConcurrentModificationException。
     * 所以入队本身也投递到 mainHandler，让「入队」与「投递」共用一条串行通道。
     */
    private void enqueueAnnouncement(final String key, final String text, final long postTime) {
        mainHandler.post(() -> enqueueAnnouncementLocked(key, text, postTime));
    }

    private void enqueueAnnouncementLocked(String key, String text, long postTime) {
        long now = SystemClock.elapsedRealtime();
        // 进程被冻结时，通知回调会排队到解冻后才送达。这时 enqueuedAt 只是
        // 「刚收到」的时刻，而通知本身可能已经是十分钟前的了——验证码、导航、
        // 聊天都会被念成过时信息。用系统的 postTime 判断真实新鲜度。
        long wallAge = postTime > 0 ? System.currentTimeMillis() - postTime : 0L;
        if (wallAge > MAX_QUEUE_AGE_MS) {
            Log.i(TAG, "drop stale notification, postTime age=" + (wallAge / 1000L) + "s");
            recordEvent("跳过 " + (wallAge / 1000L) + " 秒前的旧通知");
            return;
        }
        pendingAnnouncements.removeIf(item -> item.key.equals(key));
        pendingAnnouncements.removeIf(item -> now - item.queuedAt > MAX_QUEUE_AGE_MS);
        while (pendingAnnouncements.size() >= MAX_PENDING_ANNOUNCEMENTS) {
            pendingAnnouncements.removeFirst();
        }
        pendingAnnouncements.addLast(new PendingAnnouncement(key, text, now, now, postTime));
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
     *
     * 原值必须同时落盘（{@link SpeechVolumeGuard}）：本 ROM 会把本进程冻进 freezer，
     * app 内的恢复定时器跟着停摆，只有磁盘上的记录能让重启后的自己、保活服务和
     * root 守护把这件事收尾。
     */
    private void boostVolumeForSpeech() {
        if (audioManager == null) {
            return;
        }
        if (savedMusicVolume >= 0) {
            // 内存里还记着「提过音量」，但磁盘记录可能已经被外部（保活服务、
            // root 守护）按过期租约还原并删掉了。这时继续跳过 boost，整段会话
            // 都会在没提升过的音量里念完——所以要先核对磁盘。
            if (SpeechVolumeGuard.isPresent(this)) {
                return;
            }
            Log.i(TAG, "volume marker gone, re-boosting for this session.");
            savedMusicVolume = -1;
            boostedMusicVolume = -1;
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
                boostedMusicVolume = target;
                // 先落盘再改音量：顺序反过来的话，改完立刻被冻住就一份记录都不剩
                SpeechVolumeGuard.record(this, current, target, max,
                        System.currentTimeMillis() + VOLUME_SAFETY_TIMEOUT_MS);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                armVolumeSafetyRestore();
                Log.i(TAG, "volume boosted " + current + " -> " + target + " (max " + max + ")");
            } catch (RuntimeException exception) {
                savedMusicVolume = -1;
                SpeechVolumeGuard.clear(this);
                Log.w(TAG, "volume boost failed", exception);
            }
        }
    }

    /**
     * 每条 utterance 都重新武装兜底恢复，并顺延磁盘标记的 mtime。
     * 串行投递期间 savedMusicVolume 一直保持高位不再重置，只在会话开头武装一次的话，
     * 一个念得慢的长队列会在 2 分钟处被兜底降回原音量，后面几条就在小声里念完。
     */
    private void armVolumeSafetyRestore() {
        mainHandler.removeCallbacks(volumeSafetyRestore);
        mainHandler.postDelayed(volumeSafetyRestore, VOLUME_SAFETY_TIMEOUT_MS);
        if (savedMusicVolume < 0 || audioManager == null) {
            return;
        }
        // 续租：文件在整个会话期间都存在，只看「文件在」就恢复的话，
        // 守护会在念到一半时把音量压回去。每次开口都把租约往后推。
        SpeechVolumeGuard.refresh(this, savedMusicVolume, boostedMusicVolume,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                System.currentTimeMillis() + VOLUME_SAFETY_TIMEOUT_MS);
    }

    /** 启动守卫：speak() 返回 SUCCESS 之后，引擎必须尽快回调 onStart。 */
    private void armStartGuard() {
        mainHandler.removeCallbacks(finishGuard);
        mainHandler.removeCallbacks(startGuard);
        // 必须用 uptimeMillis：Handler.postDelayed 也是按 uptime 计时（不含系统休眠）。
        // 用 elapsedRealtime 的话，设备深度休眠会被算成「迟到」，
        // 把正常唤醒误判成解冻假超时。
        startGuardDueMs = SystemClock.uptimeMillis() + START_GUARD_MS;
        startGuardGraced = false;
        mainHandler.postDelayed(startGuard, START_GUARD_MS);
    }

    /** 完成守卫：onStart 之后按字数估算，封顶 UTTERANCE_TIMEOUT_MS。 */
    private void armFinishGuard() {
        mainHandler.removeCallbacks(startGuard);
        mainHandler.removeCallbacks(finishGuard);
        long chars = currentItem == null ? 0L : currentItem.text.length();
        long guard = SPEECH_GUARD_BASE_MS + SPEECH_GUARD_PER_CHAR_MS * chars;
        guard = Math.min(guard, UTTERANCE_TIMEOUT_MS);
        finishGuardDueMs = SystemClock.uptimeMillis() + guard;
        finishGuardGraced = false;
        mainHandler.postDelayed(finishGuard, guard);
    }

    private void cancelGuards() {
        mainHandler.removeCallbacks(startGuard);
        mainHandler.removeCallbacks(finishGuard);
    }

    /**
     * 焦点被短暂抢占时把队列的年龄基准推后，但**必须有上限**。
     *
     * 无上限的顺延等于废掉新鲜度：一个 40 分钟的电话结束后，会把 40 分钟前的通知
     * 一条条念出来。这里只允许相对「原始进队时刻」顺延 60 秒，超过就让它自然过期。
     */
    private void deferQueueAge() {
        long now = SystemClock.elapsedRealtime();
        for (PendingAnnouncement item : pendingAnnouncements) {
            long cap = item.enqueuedAt + QUEUE_AGE_DEFER_MAX_MS;
            item.queuedAt = Math.min(now, cap);
        }
    }

    private static final long QUEUE_AGE_DEFER_MAX_MS = 60_000L;

    /**
     * 延迟 400ms 再还原：A2DP 管线有 200~300ms 延迟，部分引擎的 onDone 表示
     * 「合成结束」而不是「播放结束」，立刻还原会把最后半句压在低位里。
     */
    private void scheduleVolumeRestore() {
        mainHandler.removeCallbacks(pendingVolumeRestore);
        mainHandler.postDelayed(pendingVolumeRestore, VOLUME_RESTORE_DELAY_MS);
    }

    private void cancelPendingVolumeRestore() {
        mainHandler.removeCallbacks(pendingVolumeRestore);
    }

    private void restoreVolumeAfterSpeech() {
        mainHandler.removeCallbacks(volumeSafetyRestore);
        int original = savedMusicVolume;
        savedMusicVolume = -1;
        boostedMusicVolume = -1;
        if (original < 0) {
            // 本轮根本没提过音量：磁盘上那份记录不属于本进程，不能在这里删。
            // 之前无条件 clear 会踩掉「另一个正在播报的会话」的租约，
            // 让对方的音量在念到一半时被兜底逻辑压回原值。
            return;
        }
        // 只有确实还原了才删记录，否则下次启动会被当成「上次没还原」再改一次音量
        SpeechVolumeGuard.clear(this);
        if (audioManager != null) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0);
                Log.i(TAG, "volume restored to " + original);
            } catch (RuntimeException exception) {
                Log.w(TAG, "volume restore failed", exception);
            }
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
        activeToken = null;
        currentItem = null;
        speechPhase = SpeechPhase.IDLE;
        cancelGuards();
        cancelPendingVolumeRestore();
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
                // 引擎真正开口了：启动守卫换成按字数估的完成守卫。
                // 全部 post 到同一个 Handler：回调来自 binder 线程，冻结解冻后会集中
                // 爆发，必须串行化，否则状态迁移互相踩。
                mainHandler.post(() -> {
                    if (!utteranceId.equals(activeToken)) {
                        return;
                    }
                    armFinishGuard();
                });
            }

            @Override
            public void onDone(String utteranceId) {
                Log.i(TAG, "TTS onDone " + utteranceId);
                mainHandler.post(() -> onUtteranceFinished(utteranceId, false));
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "TTS onError " + utteranceId);
                mainHandler.post(() -> onUtteranceFinished(utteranceId, true));
            }

            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                Log.i(TAG, "TTS onStop " + utteranceId + " interrupted=" + interrupted);
                mainHandler.post(() -> onUtteranceFinished(utteranceId, false));
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
    /**
     * 用户关掉播报（App 内开关或快捷磁贴）时必须立刻停下。
     * 只写 SharedPreferences 是不够的：队列里最多还压着 5 条，引擎里还有已排队的
     * utterance，它们会继续念几十秒，表现就是「关了还在念」。
     */
    static void stopEverything(Context context) {
        NotificationReaderService service = instance;
        if (service == null) {
            return;
        }
        service.mainHandler.post(() -> {
            if (service.pendingAnnouncements.isEmpty()
                    && service.speechPhase == SpeechPhase.IDLE
                    && service.savedMusicVolume < 0) {
                // 已经在关着的空档：onResume 每次都调过来，别反复 stop 引擎刷日志
                return;
            }
            service.endSpeechSessionNow();
            service.pendingAnnouncements.clear();
            if (service.textToSpeech != null) {
                // 先断会话（activeToken 置空）再 stop：stop() 之后引擎补发的
                // onDone/onStop 会因为 token 对不上被丢掉，不会推进到下一轮投递。
                service.textToSpeech.stop();
            }
            recordEvent("播报已关闭，队列与引擎队列一并清空");
        });
    }

    /**
     * 逐条投递：一次只把一条交给引擎，念完的回调里再取队首。
     * 旧实现用一个 while 循环把整队 QUEUE_ADD 塞进引擎，于是
     * 「过期不念」「同 key 只留最新」全部失效——已经进了引擎队列的内容撤不回来；
     * 焦点一丢 stop() 就被清空，注释承诺的「队列留着稍后重试」并不成立。
     */
    private void drainAnnouncementQueue() {
        if (speechPhase != SpeechPhase.IDLE) {
            // SPEAKING：当前这条还没念完，等回调；PAUSED：焦点被占着，
            // 这时候投递等于在人家说话时插嘴，而且会把暂停态又推回 SPEAKING。
            return;
        }
        if (pendingAnnouncements.isEmpty()) {
            endSpeechSession();
            return;
        }
        // 兜底：任何延迟重试（焦点、朗读失败）都不该在开关关闭后再把内容念出来
        if (!getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_ENABLED, true)) {
            Log.i(TAG, "drain: announcements disabled, dropping queue.");
            pendingAnnouncements.clear();
            endSpeechSession();
            return;
        }
        // 用 1 秒缓存的蓝牙状态即可：hasBluetoothAudioOutput 每次都跨 binder 查
        // AudioManager，而这里的判定不依赖亚秒级精度。
        if (!isBluetoothConnected()) {
            Log.i(TAG, "drain: no bluetooth audio output, dropping queue.");
            pendingAnnouncements.clear();
            endSpeechSession();
            return;
        }
        if (!ttsReady) {
            Log.i(TAG, "drain: TTS not ready yet, keeping queue.");
            endSpeechSession();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        collapseStaleIntoSummary(now);
        while (!pendingAnnouncements.isEmpty()) {
            PendingAnnouncement item = pendingAnnouncements.removeFirst();
            int dropped = 0;
            if (now - item.queuedAt > MAX_QUEUE_AGE_MS) {
                Log.i(TAG, "drop stale announcement");
                dropped = 1;
            }
            reportDropped(dropped);
            // 投出去就收手，剩下是 onUtteranceFinished 的事；
            // speak 失败时它已把这条放回队首并安排了重试。
            if (speak(item)) {
                return;
            }
            // 失败且这条已过期被丢弃时队列会是空的：会话必须收掉，
            // 否则焦点和提升后的音量一直挂着。
            if (pendingAnnouncements.isEmpty()) {
                endSpeechSession();
            }
            return;
        }
        // 走到这里说明队列真的空了：过期条目已经在 collapseStaleIntoSummary
        // 里合并成一条摘要并投递过了，不该再静默丢弃。
        endSpeechSession();
    }

    /**
     * 把这一轮扫到的过期条目合并成一条摘要，而不是静默丢掉。
     *
     * 静默丢弃对使用者最难受：他只知道「刚才好像响过一条通知」，事后完全无法回忆。
     * 合并成一条「已跳过 n 条未播报的通知」至少给出计数。摘要本身不参与再合并，
     * 新摘要替换旧摘要并累加计数，避免出现「摘要的摘要」。
     */
    private void collapseStaleIntoSummary(long now) {
        int stale = 0;
        java.util.Iterator<PendingAnnouncement> iterator = pendingAnnouncements.iterator();
        while (iterator.hasNext()) {
            PendingAnnouncement item = iterator.next();
            if (item.summary) {
                continue;
            }
            // 两条判据都要看：queuedAt 管「入队后放了多久」，postTime 管
            // 「通知本身生成多久了」——后者才能识别冻结期间积压的旧回调。
            boolean expired = now - item.queuedAt > MAX_QUEUE_AGE_MS;
            if (!expired && item.postTime > 0) {
                expired = System.currentTimeMillis() - item.postTime > MAX_QUEUE_AGE_MS;
            }
            if (expired) {
                iterator.remove();
                stale++;
            }
        }
        if (stale == 0) {
            return;
        }
        java.util.Iterator<PendingAnnouncement> previous = pendingAnnouncements.iterator();
        while (previous.hasNext()) {
            PendingAnnouncement item = previous.next();
            if (item.summary) {
                stale += item.summaryCount;
                previous.remove();
                break;
            }
        }
        PendingAnnouncement summary =
                new PendingAnnouncement("__summary__", "已跳过 " + stale + " 条未播报的通知",
                        now, now, 0L);
        summary.summary = true;
        summary.summaryCount = stale;
        pendingAnnouncements.addFirst(summary);
        recordEvent("合并 " + stale + " 条过期通知为一条摘要");
    }

    /** 过期的报一次就够，别让用户在「最近处理记录」里看到一串重复行。 */
    private void reportDropped(int dropped) {
        if (dropped > 0) {
            recordEvent("跳过 " + dropped + " 条过期通知");
        }
    }

    /**
     * 投一条。队列语义归 drainAnnouncementQueue，这里只负责「这一次开口」。
     * 用 QUEUE_FLUSH 而非 QUEUE_ADD：串行投递下引擎队列里本来就只有这一条，
     * FLUSH 顺手清掉上一次会话被 stop() 打断后可能残留的尾巴。
     */
    private boolean speak(PendingAnnouncement item) {
        if (textToSpeech == null) {
            Log.e(TAG, "speak: TTS instance is null.");
            // 引擎被回收但队列还在：回队等初始化重试，别把这条当已播报吞掉
            requeueForRetry(item, 3000L);
            return false;
        }
        boolean newSession = speechPhase != SpeechPhase.SPEAKING;
        if (newSession) {
            if (!requestAudioFocus()) {
                Log.w(TAG, "speak: could not obtain audio focus.");
                recordEvent("播报被跳过：音频焦点被其他应用占用，稍后重试");
                requeueForRetry(item, 3000L);
                return false;
            }
            sessionSeq++;
            speechPhase = SpeechPhase.SPEAKING;
            // 新会话才提音量、才放提示音；会话中间动音量会让两条之间「掉一下声音」
            boostVolumeForSpeech();
            if (item.retryCount == 0 && item.replayCount == 0 && !item.summary) {
                playBeepIfNeeded();
            }
        }
        // 会话延续：取消上一次排队的还原
        cancelPendingVolumeRestore();
        currentItem = item;
        armVolumeSafetyRestore();
        // token 带会话号 + 条目号 + 重试次数：被冻结期间排队、解冻后集中爆发
        // 的旧回调，以及 stop() 之后补发的回调，都会因为对不上而被丢弃。
        // 重试次数与重播次数必须分开编码：两者混在一起时，一次重试会和上一次
        // 失败留下的幽灵回调撞出同样的 id，那条回调就会被当成「当前这条」。
        String token = "e" + engineEpoch + "-s" + sessionSeq + "-" + (++itemSeq)
                + "-r" + item.retryCount + "-p" + item.replayCount;
        activeToken = token;
        int result = textToSpeech.speak(
                item.text, TextToSpeech.QUEUE_FLUSH, new Bundle(), token);
        Log.i(TAG, "speak result=" + result + " text=" + item.text);
        recordEvent(result == TextToSpeech.SUCCESS ? "开始朗读" : "朗读失败 (code=" + result + ")");
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "speak: TTS rejected the utterance.");
            activeToken = null;
            currentItem = null;
            if (newSession) {
                // 会话还没真正开始：焦点和音量都退回，下一次重试重新走一遍
                endSpeechSessionNow();
            }
            // 先回队再重建：顺序反过来的话，重建完这条已经因为失败次数到顶被丢掉，
            // 那就白重建了——最后一次尝试必须落在新引擎上。
            requeueForRetry(item, 4000L);
            speakFailStreak++;
            if (speakFailStreak >= MAX_SPEAK_FAIL_STREAK) {
                rebuildTextToSpeech("连续 " + speakFailStreak + " 次被引擎拒绝");
            }
            return false;
        }
        speakFailStreak = 0;
        armStartGuard();
        return true;
    }

    /**
     * 没念出来的内容不能算「已播报」：isNewNotification 已经把这条标成最新内容，
     * 不回队的话这条通知就永久丢了。但重试必须有上限，否则一条永远念不出的内容
     * 会让引擎空转下去。
     */
    private void requeueForRetry(PendingAnnouncement item, long delayMs) {
        if (SystemClock.elapsedRealtime() - item.queuedAt > MAX_QUEUE_AGE_MS) {
            Log.i(TAG, "retry dropped: announcement already stale");
            return;
        }
        if (item.retryCount >= MAX_SPEAK_RETRIES) {
            Log.w(TAG, "retry dropped: " + item.retryCount + " failures");
            recordEvent("朗读多次失败，丢弃这条通知");
            return;
        }
        item.retryCount++;
        pendingAnnouncements.addFirst(item);
        mainHandler.postDelayed(this::drainAnnouncementQueue, delayMs);
    }

    /** 启动守卫超时：speak() 说成功，引擎却一直没开口。 */
    private void onStartGuardTimeout() {
        if (speechPhase != SpeechPhase.SPEAKING || activeToken == null) {
            return;
        }
        // 假超时：进程刚被解冻时，到期的 guard 会比刚排进来的 onStart 先出队。
        // 实际触发时间比计划晚了 2 秒以上，说明线程被冻过，宽限一次。
        if (!startGuardGraced
                && SystemClock.uptimeMillis() - startGuardDueMs > 2_000L) {
            startGuardGraced = true;
            Log.i(TAG, "start guard fired late (post-thaw), granting grace.");
            armStartGuard();
            return;
        }
        Log.w(TAG, "start guard fired: engine never spoke " + activeToken);
        recordEvent("朗读超时：引擎没有开口");
        activeToken = null;
        currentItem = null;
        speakFailStreak++;
        if (speakFailStreak >= MAX_SPEAK_FAIL_STREAK) {
            rebuildTextToSpeech("引擎一直没有开口");
        }
        drainAnnouncementQueue();
    }

    /** 完成守卫超时：开口了但迟迟不回调 onDone/onError。 */
    private void onFinishGuardTimeout() {
        if (speechPhase != SpeechPhase.SPEAKING || activeToken == null) {
            return;
        }
        if (!finishGuardGraced
                && SystemClock.uptimeMillis() - finishGuardDueMs > 2_000L) {
            finishGuardGraced = true;
            Log.i(TAG, "finish guard fired late (post-thaw), granting grace.");
            armFinishGuard();
            return;
        }
        Log.w(TAG, "finish guard fired " + activeToken);
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        // stop() 之后引擎补发的 onStop 会因为 token 已置空被丢弃
        activeToken = null;
        currentItem = null;
        drainAnnouncementQueue();
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
                    mainHandler.post(() -> onAudioFocusChanged(focusChange));
                })
                .build();
        boolean granted = audioManager.requestAudioFocus(audioFocusRequest)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        Log.i(TAG, "requestAudioFocus granted=" + granted);
        return granted;
    }

    /**
     * 引擎回调统一的出口。token 对不上的回调一律丢弃：
     * 冻结期间 oneway 回调会排队、解冻后集中爆发，而同一时刻只可能有一条在念。
     */
    private void onUtteranceFinished(String token, boolean failed) {
        if (token == null || !token.equals(activeToken)) {
            Log.i(TAG, "stale TTS callback " + token + ", ignored.");
            return;
        }
        activeToken = null;
        currentItem = null;
        if (failed) {
            speakFailStreak++;
            if (speakFailStreak >= MAX_SPEAK_FAIL_STREAK) {
                rebuildTextToSpeech("连续 " + speakFailStreak + " 次朗读失败");
            }
        } else {
            speakFailStreak = 0;
        }
        // 会话延续：队列里还有内容就接着投下一条，中间不释放焦点、不动音量，
        // 否则两条之间会「掉一下音量 + 一声提示音」，整段听感是断的。
        // 队列空了 drain 自己会调 endSpeechSession 收尾。
        drainAnnouncementQueue();
    }

    /** 会话正常收尾：延迟 400ms 还原音量，避开 A2DP 管线的尾音。 */
    private void endSpeechSession() {
        finishSession(true);
    }

    /** 需要立刻还原音量的出口：关闭播报、拔耳机、焦点被长期占用。 */
    private void endSpeechSessionNow() {
        finishSession(false);
    }

    /** 所有出口的唯一收尾点。 */
    private void finishSession(boolean delayRestore) {
        cancelGuards();
        activeToken = null;
        currentItem = null;
        speechPhase = SpeechPhase.IDLE;
        abandonAudioFocus();
        if (delayRestore) {
            scheduleVolumeRestore();
        } else {
            cancelPendingVolumeRestore();
            restoreVolumeAfterSpeech();
        }
        // 不变量：IDLE 必须意味着「没有在念、没有焦点、没有守卫」。
        // 这里只做诊断——真漏了置位时，靠这行日志能一眼看出是哪个出口漏的。
        if (activeToken != null || currentItem != null || audioFocusRequest != null) {
            Log.w(TAG, "session invariant broken at IDLE: token=" + activeToken
                    + " item=" + (currentItem == null ? "null" : "set")
                    + " focus=" + (audioFocusRequest != null));
        }
    }

    /** 暂停：撤守卫、放焦点，但**不**还原音量——会话还要继续，还原会掉一下声音。 */
    private void pauseSpeechSession() {
        cancelGuards();
        activeToken = null;
        currentItem = null;
        speechPhase = SpeechPhase.PAUSED;
        abandonAudioFocus();
    }

    private void resumeFromPause() {
        if (speechPhase != SpeechPhase.PAUSED) {
            return;
        }
        // 先试探焦点再续播：通话或导航独占焦点时无条件 drain 等于强行插嘴。
        // 试探成功后立刻放手，真正的会话由 speak() 里重新申请。
        if (!requestAudioFocus()) {
            Log.i(TAG, "resume deferred: audio focus still held by others.");
            mainHandler.postDelayed(this::resumeFromPause, 15_000L);
            return;
        }
        abandonAudioFocus();
        speechPhase = SpeechPhase.IDLE;
        drainAnnouncementQueue();
    }

    /** 引擎连续失败时重建：不重建的话整条会话会一直空转到队列全部过期。 */
    private void rebuildTextToSpeech(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (now - rebuildWindowStartMs > REBUILD_WINDOW_MS) {
            rebuildWindowStartMs = now;
            rebuildCount = 0;
        }
        if (rebuildCount >= MAX_REBUILD_PER_WINDOW) {
            // 引擎反复坏掉时无限重建只会持续占用 CPU，而且照样念不出来
            Log.w(TAG, "rebuild suppressed (" + rebuildCount + " in window): " + reason);
            recordEvent("TTS 引擎反复失败，已暂停自动重建");
            return;
        }
        rebuildCount++;
        // 代数先加：旧引擎在 shutdown 之后仍可能补发 onDone/onStop，
        // 只靠会话号+条目号会让它们和新 token 撞成同一串。
        engineEpoch++;
        long delay = Math.min(3_000L * rebuildCount, 30_000L);
        Log.w(TAG, "rebuilding TTS engine: " + reason);
        recordEvent("重建 TTS 引擎：" + reason);
        speakFailStreak = 0;
        ttsReady = false;
        TextToSpeech broken = textToSpeech;
        textToSpeech = null;
        if (broken != null) {
            try {
                broken.stop();
                broken.shutdown();
            } catch (RuntimeException ignored) {
                // 引擎已经坏了，关闭失败没有补救手段
            }
        }
        if (!destroyed) {
            // 退避：重建是异步的（等 onInit），期间 ttsReady=false，条目留在队里
            mainHandler.postDelayed(this::initializeTextToSpeech, delay);
        }
    }

    /**
     * 焦点变化分三种处理，关键区别是「已标记已播报的内容会不会丢」：
     * GAIN —— 对方让回来了，从暂停态恢复续播；
     * LOSS_TRANSIENT_CAN_DUCK —— 对方允许只降不停。**不自己拧音量**：
     *   boost/restore 动的是 STREAM_MUSIC，导航提示音和用户的音乐都在同一条流上，
     *   压一半等于把它们一起压掉，而通知是叠在导航上面的语音，压自己并不会
     *   让自己「更清楚」。这里只保持继续播，让系统按 CONTENT_TYPE_SPEECH 去处理；
     * LOSS_TRANSIENT / LOSS —— 必须停嘴。TRANSIENT 保留积压并把被掐断的
     *   这条回队重播一次（它已经算「已播报」），LOSS 短期回不来，直接丢弃积压。
     */
    private void onAudioFocusChanged(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            if (speechPhase == SpeechPhase.PAUSED) {
                Log.i(TAG, "audio focus gained, resuming.");
                resumeFromPause();
            }
            return;
        }
        if (speechPhase == SpeechPhase.IDLE && pendingAnnouncements.isEmpty()) {
            return;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            Log.i(TAG, "audio focus can_duck: keep speaking at full volume.");
            return;
        }
        boolean permanent = focusChange == AudioManager.AUDIOFOCUS_LOSS;
        Log.i(TAG, "audio focus lost, focusChange=" + focusChange
                + " permanent=" + permanent);
        if (permanent) {
            endSpeechSessionNow();
            pendingAnnouncements.clear();
            recordEvent("焦点被长期占用，已停止播报并丢弃积压");
            return;
        }
        // 短暂抢占（来电、导航播报）：stop() 会掐断当前这条，而它已经算「已播报」，
        // 不回队就是静默丢失。每条只重播一次，避免「念不完→被打断→再念」的死循环。
        PendingAnnouncement interrupted = currentItem;
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        pauseSpeechSession();
        if (interrupted != null && !interrupted.summary && interrupted.replayCount < 1) {
            interrupted.replayCount++;
            interrupted.retryCount = 0;
            pendingAnnouncements.addFirst(interrupted);
        }
        // 暂停由外界造成，队列年龄顺延（有上限），别因此把整批消息丢掉
        deferQueueAge();
        recordEvent("焦点被短暂抢占，已暂停，稍后继续");
        mainHandler.postDelayed(this::resumeFromPause, 5000L);
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
        /** 原始进队时刻：队列年龄顺延的基线，本身永不改动。 */
        final long enqueuedAt;
        /** 过期判定用的时刻，焦点被短暂抢占时顺延（相对 enqueuedAt 最多 60 秒）。 */
        long queuedAt;
        /** speak() 失败重试次数：重试过的条目不再放提示音。 */
        int retryCount;
        /** 被焦点抢占重播的次数：每条最多一次，防「念不完→被打断→再念」的死循环。 */
        int replayCount;
        /** 是否为「n 条未播报」的合并摘要：摘要不参与再合并。 */
        boolean summary;
        int summaryCount;
        /** 通知系统的发布时间（墙钟毫秒）：冻结期间积压的回调靠它识别真实新旧。 */
        final long postTime;

        PendingAnnouncement(String key, String text, long enqueuedAt, long queuedAt,
                long postTime) {
            this.key = key;
            this.text = text;
            this.enqueuedAt = enqueuedAt;
            this.queuedAt = queuedAt;
            this.postTime = postTime;
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
