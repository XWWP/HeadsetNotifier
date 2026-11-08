package com.sakura.airannounce;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final String PREFERENCES_NAME = "airannounce_settings";
    static final String KEY_ENABLED = "announcements_enabled";
    static final String KEY_ALLOWED_PACKAGES = "allowed_packages";
    static final String KEY_BOOST_VOLUME = "boost_volume";
    static final String KEY_VOLUME_PERCENT = "volume_percent";
    static final String KEY_READ_APP_NAME = "read_app_name";
    static final String KEY_READ_TITLE = "read_title";
    static final String KEY_READ_BODY = "read_body";
    static final String KEY_SPEECH_RATE = "speech_rate";
    static final String KEY_BEEP = "beep_before_speech";
    static final String KEY_DND_ENABLED = "dnd_enabled";
    static final String KEY_DND_START = "dnd_start_hour";
    static final String KEY_DND_END = "dnd_end_hour";
    static final String KEY_POLL_INTERVAL = "poll_interval_ms";

    static final int DEFAULT_VOLUME_PERCENT = 80;
    static final int DEFAULT_SPEECH_RATE = 100;
    static final int DEFAULT_POLL_INTERVAL_MS = 5000;

    // —— MIUIX 配色 ——
    static final int ACCENT_COLOR = 0xFF3482FF;
    static final int BG_COLOR = 0xFFF2F3F5;
    static final int CARD_COLOR = 0xFFFFFFFF;
    static final int TEXT_PRIMARY = 0xFF191919;
    static final int TEXT_SECONDARY = 0xFF6B6B6B;
    static final int TEXT_TERTIARY = 0xFF9A9A9A;
    static final int TONAL_COLOR = 0xFFEDF3FF;
    static final int DIVIDER_COLOR = 0xFFF0F0F0;

    static volatile boolean isForeground;

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int REQUEST_PICK_APPS = 1002;

    private SharedPreferences preferences;
    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable permissionAuditRunnable = this::runPermissionAudit;
    private TextView heroDeviceView;
    private TextView heroStateView;
    private GradientDrawable heroStateChipBg;
    private Switch enabledSwitch;
    private AlertDialog auditDialog;
    private ScrollView mainScrollView;
    private TextView shizukuStateLabel;
    private Button shizukuGrantButton;
    private Button shizukuApplyButton;
    private final Switch.OnCheckedChangeListener enabledSwitchListener = (button, checked) -> {
        preferences.edit().putBoolean(KEY_ENABLED, checked).apply();
        syncKeepAlive(this);
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (prefs, key) -> {
                // 快捷磁贴改 KEY_ENABLED 时 Activity 往往不走 onResume，
                // 不监听就会出现「磁贴已关、首页开关还亮着」
                if (KEY_ENABLED.equals(key)) {
                    runOnUiThread(MainActivity.this::updateStatus);
                }
            };
    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this,
                        grantResult == PackageManager.PERMISSION_GRANTED
                                ? "Shizuku 权限已授予" : "Shizuku 权限被拒绝",
                        Toast.LENGTH_SHORT).show();
                // 授权回来后弹窗里的状态行必须跟着变，否则还停在「尚未授权」
                rerunShizukuRefresh();
            });
    private TextToSpeech testTts;
    private boolean testTtsReady;
    private boolean pendingSpeakAfterInit;
    private int testSavedVolume = -1;
    private boolean guidePending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        buildLayout();
        // TTS 引擎占内存不小，改为点「测试语音」时才初始化，避免只为看一眼界面就拉起引擎
        boolean requested = requestRuntimePermissionsIfNeeded();
        // Shizuku 授权结果走异步回调，先挂上监听
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
            // 没装 Shizuku 时初始化失败不影响主流程
        }
        if (!preferences.getBoolean(KEY_FIRST_LAUNCH_DONE, false)) {
            preferences.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply();
            guidePending = true;
        }
        if (guidePending && !requested) {
            uiHandler.postDelayed(permissionAuditRunnable, 600L);
        }
    }

    static final String KEY_FIRST_LAUNCH_DONE = "first_launch_guide_done";

    /** 首页权限卡片的状态指纹，用于判断从系统设置回来后是否需要重绘。 */
    private String lastPermissionSnapshot;

    private String permissionSnapshot() {
        return (hasNotificationAccess() ? "1" : "0")
                + "|" + (hasBluetoothAudioOutput(this) ? "1" : "0")
                + "|" + (isIgnoringBatteryOptimizations() ? "1" : "0")
                + "|" + sensitiveNotificationState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        rerunShizukuRefresh();
        // 从系统设置授权回来时，权限卡片必须跟着变，否则用户以为没生效
        String snapshot = permissionSnapshot();
        if (lastPermissionSnapshot != null && !lastPermissionSnapshot.equals(snapshot)) {
            buildLayout();
        }
        lastPermissionSnapshot = snapshot;
        updateStatus();
        // 统一走 syncKeepAlive：关掉播报后不该因为重开 App 又把前台服务拉起来
        syncKeepAlive(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false;
        try {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        isForeground = false;
        uiHandler.removeCallbacksAndMessages(null);
        try {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        } catch (RuntimeException ignored) {
        }
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
        }
        // 测试播报中途退出：shutdown 之后引擎不会再回调，音量只能在这里主动恢复
        restoreTestVolume();
        if (testTts != null) {
            testTts.stop();
            testTts.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            updateStatus();
            if (guidePending) {
                guidePending = false;
                runPermissionAudit();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_APPS && resultCode == RESULT_OK && data != null) {
            String packages = data.getStringExtra(AppPickerActivity.RESULT_PACKAGES);
            if (packages == null) {
                packages = "";
            }
            preferences.edit().putString(KEY_ALLOWED_PACKAGES, packages).apply();
            updateStatus();
            // 首页「播报白名单」右侧的应用数量需要跟着刷新
            buildLayout();
            Toast.makeText(this, R.string.allowed_packages_saved, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 布局 ----------------

    private void buildLayout() {
        int restoreScroll = mainScrollView == null ? 0 : mainScrollView.getScrollY();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(BG_COLOR);
        content.setPadding(dp(16), dp(24), dp(16), dp(24));

        content.addView(createAppHeader(), matchWrap());

        LinearLayout.LayoutParams heroParams = matchWrap();
        heroParams.topMargin = dp(4);
        content.addView(createStatusHero(), heroParams);

        LinearLayout.LayoutParams card1 = matchWrap();
        card1.topMargin = dp(12);
        content.addView(createMasterSwitchCard(), card1);

        LinearLayout.LayoutParams card2 = matchWrap();
        card2.topMargin = dp(12);
        content.addView(createPermissionCard(), card2);

        LinearLayout.LayoutParams card3 = matchWrap();
        card3.topMargin = dp(12);
        content.addView(createSettingsEntryCard(), card3);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));
        content.addView(spacer);

        ScrollView scrollView = new ScrollView(this);
        mainScrollView = scrollView;
        scrollView.addView(content);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_COLOR);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(createBottomBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        if (restoreScroll > 0) {
            // 保存白名单、从系统设置回来都会整树重建，不还原就会跳回顶部
            scrollView.post(() -> scrollView.scrollTo(0, restoreScroll));
        }
        // 建树时胶囊是写死的初值，重建后必须再对齐一次真实状态，
        // 否则保存设置回来会把「需要通知权限/已暂停」谎报成「等待耳机」
        updateStatus();
    }

    private View createAppHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, dp(8), 0, dp(12));

        LinearLayout logo = new LinearLayout(this);
        logo.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setColor(0xFFFFFFFF);
        logoBg.setCornerRadius(dp(12));
        logo.setBackground(logoBg);
        logo.setElevation(dp(2));
        logo.setGravity(android.view.Gravity.CENTER);
        int size = dp(44);
        logo.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int iconSize = dp(40);
        logo.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        header.addView(logo);

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        titleGroup.setPadding(dp(12), 0, 0, 0);
        titleGroup.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(R.string.title);
        title.setTextSize(22);
        title.setTextColor(TEXT_PRIMARY);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleGroup.addView(title);

        TextView desc = new TextView(this);
        desc.setText(R.string.description);
        desc.setTextSize(14);
        desc.setTextColor(TEXT_SECONDARY);
        desc.setPadding(0, dp(4), 0, 0);
        titleGroup.addView(desc);

        header.addView(titleGroup);
        return header;
    }

    private View createStatusHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        hero.setGravity(android.view.Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_COLOR);
        bg.setCornerRadius(dp(20));
        hero.setBackground(bg);

        LinearLayout iconWrap = new LinearLayout(this);
        int wrapSize = dp(54);
        iconWrap.setLayoutParams(new LinearLayout.LayoutParams(wrapSize, wrapSize));
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor(ACCENT_COLOR);
        iconBg.setCornerRadius(dp(16));
        iconWrap.setBackground(iconBg);
        iconWrap.setGravity(android.view.Gravity.CENTER);
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.drawable.ic_headset_white);
        int icSize = dp(28);
        iconWrap.addView(icon, new LinearLayout.LayoutParams(icSize, icSize));
        hero.addView(iconWrap);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(14), 0, dp(10), 0);
        info.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView label = new TextView(this);
        label.setText("当前状态");
        label.setTextSize(12);
        label.setTextColor(TEXT_TERTIARY);
        info.addView(label);

        TextView device = new TextView(this);
        device.setText(hasBluetoothAudioOutput(this) ? "蓝牙耳机已连接" : "未连接蓝牙音频");
        device.setTextSize(17);
        device.setTextColor(TEXT_PRIMARY);
        device.setTypeface(null, android.graphics.Typeface.BOLD);
        device.setPadding(0, dp(3), 0, 0);
        info.addView(device);
        heroDeviceView = device;
        hero.addView(info);

        TextView state = new TextView(this);
        state.setTextSize(12);
        state.setTypeface(null, android.graphics.Typeface.BOLD);
        heroStateChipBg = new GradientDrawable();
        heroStateChipBg.setCornerRadius(dp(14));
        heroStateChipBg.setColor(TONAL_COLOR);
        state.setBackground(heroStateChipBg);
        state.setTextColor(ACCENT_COLOR);
        state.setPadding(dp(12), dp(6), dp(12), dp(6));
        state.setText("等待耳机");
        heroStateView = state;
        hero.addView(state);

        return hero;
    }

    private View createMasterSwitchCard() {
        LinearLayout card = createCard(0xFFFFFFFF, dp(16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(R.string.enable_announcements);
        t.setTextSize(16);
        t.setTextColor(TEXT_PRIMARY);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        txt.addView(t);

        TextView d = new TextView(this);
        d.setText("连接耳机后自动朗读新通知");
        d.setTextSize(13);
        d.setTextColor(TEXT_SECONDARY);
        d.setPadding(0, dp(2), 0, 0);
        txt.addView(d);

        row.addView(txt);

        enabledSwitch = new Switch(this);
        enabledSwitch.setChecked(preferences.getBoolean(KEY_ENABLED, true));
        enabledSwitch.setOnCheckedChangeListener(enabledSwitchListener);
        row.addView(enabledSwitch);

        card.addView(row);
        return card;
    }

    private View createPermissionCard() {
        LinearLayout card = createCard(0xFFFFFFFF, dp(16));

        TextView title = new TextView(this);
        title.setText(R.string.section_status);
        title.setTextSize(13);
        title.setTextColor(TEXT_TERTIARY);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8));
        card.addView(title);

        boolean access = hasNotificationAccess();
        boolean bt = hasBluetoothAudioOutput(this);
        boolean battery = isIgnoringBatteryOptimizations();

        card.addView(createPermRow(access, "通知读取权限",
                access ? "已授权，可访问通知栏内容" : "未授权，无法读取通知", !access, this::openNotificationSettings));
        card.addView(createPermRow(bt, "蓝牙连接",
                bt ? "已连接蓝牙音频设备" : "未连接蓝牙音频设备", false, null));
        card.addView(createPermRow(battery, "电池优化",
                battery ? "已豁免，后台不会被系统限制" : "未豁免，可能影响后台运行", !battery, this::requestIgnoreBatteryOptimizations));
        int sensitiveState = sensitiveNotificationState();
        // 低版本没有这个开关，别拿「已允许」误导人；
        // Android 15+ 时只要不是明确已允许，都要给用户一个能点的入口
        // （之前系统未公开状态时按钮是隐藏的，等于告诉用户「有问题但没路走」）
        boolean android15 = Build.VERSION.SDK_INT >= 35;
        String sensitiveDesc;
        if (!android15) {
            sensitiveDesc = "当前系统版本无需单独开启";
        } else if (sensitiveState == 1) {
            sensitiveDesc = "已允许，可读取聊天类通知内容";
        } else if (sensitiveState == -1) {
            sensitiveDesc = "已被禁止，微信、QQ 等通知读不到内容";
        } else {
            sensitiveDesc = "系统未暴露检测接口，若聊天通知读不到请点此开启";
        }
        card.addView(createPermRow(sensitiveState == 1, "敏感通知权限", sensitiveDesc,
                android15 && sensitiveState != 1, this::openSensitiveNotificationSettings,
                "去开启"));

        return card;
    }

    private View createPermRow(boolean ok, String name, String desc, boolean showAction,
            Runnable action) {
        return createPermRow(ok, name, desc, showAction, action, "去设置");
    }

    private View createPermRow(boolean ok, String name, String desc, boolean showAction,
            Runnable action, String actionText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(11), 0, dp(11));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout iconWrap = new LinearLayout(this);
        int size = dp(32);
        iconWrap.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setCornerRadius(dp(10));
        iconWrap.setBackground(iconBg);
        iconWrap.setGravity(android.view.Gravity.CENTER);
        TextView icon = new TextView(this);
        icon.setTextSize(15);
        icon.setGravity(android.view.Gravity.CENTER);
        icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        if (ok) {
            iconBg.setColor(0xFFE6F7EE);
            icon.setText("\u2714");
            icon.setTextColor(0xFF12A05A);
        } else if (showAction) {
            iconBg.setColor(0xFFFFF3E6);
            icon.setText("\u26A0");
            icon.setTextColor(0xFFE8890C);
        } else {
            iconBg.setColor(BG_COLOR);
            icon.setText("\u2718");
            icon.setTextColor(TEXT_TERTIARY);
        }
        iconWrap.addView(icon);
        row.addView(iconWrap);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        text.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(15);
        n.setTextColor(TEXT_PRIMARY);
        text.addView(n);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12.5f);
        d.setTextColor(TEXT_SECONDARY);
        d.setPadding(0, dp(2), 0, 0);
        text.addView(d);
        row.addView(text);

        if (showAction && action != null) {
            Button btn = new Button(this);
            btn.setText(actionText);
            btn.setTextSize(13);
            btn.setTextColor(ACCENT_COLOR);
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(TONAL_COLOR);
            btnBg.setCornerRadius(dp(13));
            btn.setBackground(btnBg);
            btn.setPadding(dp(14), dp(7), dp(14), dp(7));
            btn.setMinWidth(0);
            btn.setMinimumWidth(0);
            btn.setMinHeight(0);
            btn.setMinimumHeight(0);
            btn.setAllCaps(false);
            btn.setStateListAnimator(null);
            btn.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            btnParams.leftMargin = dp(8);
            row.addView(btn, btnParams);
        }

        return row;
    }

    private View createSettingsEntryCard() {
        LinearLayout card = createCard(CARD_COLOR, dp(16));

        card.addView(createEntry("\u270F", TONAL_COLOR, ACCENT_COLOR, "播报内容",
                "应用名、标题、正文及文本处理规则", "", this::showContentDialog));
        card.addView(createDivider());
        card.addView(createEntry("\uD83D\uDD0A", 0xFFD9F5F1, 0xFF00B8A9, "语音与音量",
                "语速、音量、TTS 引擎设置", "", this::showVoiceDialog));
        card.addView(createDivider());
        card.addView(createEntry("\u2705", 0xFFFFEFE3, 0xFFE8890C, "播报白名单",
                "只播报指定应用，留空则全部播报", getWhitelistSummary(), this::showWhitelistDialog));
        card.addView(createDivider());
        card.addView(createEntry("\uD83D\uDCC5", TONAL_COLOR, ACCENT_COLOR, "勿扰时段",
                preferences.getBoolean(KEY_DND_ENABLED, false) ? "已开启" : "已关闭",
                "", this::showDndDialog));
        card.addView(createDivider());
        card.addView(createEntry("\u2699", BG_COLOR, TEXT_SECONDARY, "高级设置",
                "后台轮询、守护脚本、系统集成", "", this::showAdvancedDialog));

        return card;
    }

    private View createDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(DIVIDER_COLOR);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.leftMargin = dp(50);
        divider.setLayoutParams(params);
        return divider;
    }

    private String getWhitelistSummary() {
        String allowed = preferences.getString(KEY_ALLOWED_PACKAGES, "");
        if (allowed == null || allowed.trim().isEmpty()) return "播报全部应用";
        int count = 0;
        for (String entry : allowed.split(",")) {
            if (!entry.trim().isEmpty()) count++;
        }
        return count + " 个应用";
    }

    private View createEntry(String emoji, int bgColor, int iconColor, String title,
                             String desc, String meta, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));
        row.setClickable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(v -> onClick.run());

        LinearLayout iconWrap = new LinearLayout(this);
        int size = dp(36);
        iconWrap.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setCornerRadius(dp(11));
        iconBg.setColor(bgColor);
        iconWrap.setBackground(iconBg);
        iconWrap.setGravity(android.view.Gravity.CENTER);
        TextView icon = new TextView(this);
        icon.setText(emoji);
        icon.setTextSize(17);
        icon.setTextColor(iconColor);
        iconWrap.addView(icon);
        row.addView(iconWrap);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, 0, 0);
        text.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(TEXT_PRIMARY);
        text.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12.5f);
        d.setTextColor(TEXT_SECONDARY);
        d.setPadding(0, dp(2), 0, 0);
        text.addView(d);
        row.addView(text);

        if (meta != null && !meta.isEmpty()) {
            TextView m = new TextView(this);
            m.setText(meta);
            m.setTextSize(13);
            m.setTextColor(TEXT_TERTIARY);
            row.addView(m);
        }

        TextView chevron = new TextView(this);
        chevron.setText("\u203A");
        chevron.setTextSize(22);
        chevron.setTextColor(0xFFC8C8C8);
        chevron.setPadding(dp(8), 0, 0, 0);
        row.addView(chevron);

        return row;
    }

    private View createBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(16), dp(10), dp(16), dp(22));
        bar.setBackgroundColor(BG_COLOR);
        bar.setElevation(dp(6));

        GradientDrawable tonalBg = new GradientDrawable();
        tonalBg.setColor(TONAL_COLOR);
        tonalBg.setCornerRadius(dp(16));

        GradientDrawable filledBg = new GradientDrawable();
        filledBg.setColor(ACCENT_COLOR);
        filledBg.setCornerRadius(dp(16));

        Button auditBtn = new Button(this);
        auditBtn.setText(R.string.permission_audit_button);
        auditBtn.setTextColor(ACCENT_COLOR);
        auditBtn.setTextSize(15);
        auditBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        auditBtn.setAllCaps(false);
        auditBtn.setBackground(tonalBg);
        auditBtn.setMinHeight(0);
        auditBtn.setMinimumHeight(0);
        auditBtn.setPadding(0, dp(13), 0, dp(13));
        auditBtn.setStateListAnimator(null);
        auditBtn.setOnClickListener(v -> runPermissionAudit());

        Button testBtn = new Button(this);
        testBtn.setText(R.string.test_voice);
        testBtn.setTextColor(0xFFFFFFFF);
        testBtn.setTextSize(15);
        testBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        testBtn.setAllCaps(false);
        testBtn.setBackground(filledBg);
        testBtn.setMinHeight(0);
        testBtn.setMinimumHeight(0);
        testBtn.setPadding(0, dp(13), 0, dp(13));
        testBtn.setStateListAnimator(null);
        testBtn.setOnClickListener(v -> speakTestMessage());

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = dp(10);
        bar.addView(auditBtn, p);
        bar.addView(testBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    // ---------------- MIUIX 弹窗 ----------------

    /**
     * 统一弹窗：白色 28dp 圆角面板 + 底部等宽按钮组。
     *
     * 关键点是窗口背景设成透明，圆角由 panel 自己画。旧实现把圆角画在
     * windowBackground 上，内层 alert 容器的直角白底会盖住它，实际只看得到
     * 12dp 左右的圆角 —— 这就是「二级菜单棱角太锐利」的真正原因。
     */
    private AlertDialog showMiuixDialog(String title, View content, boolean scrollable,
            String positiveText, Runnable onPositive, String negativeText) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int windowWidth = Math.min(screenWidth - dp(48), dp(560));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(CARD_COLOR);
        panelBg.setCornerRadius(dp(28));
        panel.setBackground(panelBg);
        panel.setClipToOutline(true);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(20);
        titleView.setTextColor(TEXT_PRIMARY);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(dp(24), dp(22), dp(24), dp(4));
        panel.addView(titleView, matchWrap());

        if (content != null) {
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(24), dp(4), dp(24), dp(6));
            if (scrollable) {
                ScrollView scroll = new ScrollView(this);
                scroll.setVerticalScrollBarEnabled(false);
                scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                scroll.addView(content);
                // 内容不高时按内容高度显示，超过屏高 55% 才滚动
                int contentWidth = windowWidth - dp(48);
                content.measure(
                        View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int maxHeight = (int) (screenHeight * 0.55f);
                int height = Math.min(content.getMeasuredHeight(), maxHeight);
                body.addView(scroll, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(height, dp(80))));
            } else {
                body.addView(content, matchWrap());
            }
            panel.addView(body, matchWrap());
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(dp(24), dp(10), dp(24), dp(24));

        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.MiuixDialogTheme)
                .setView(panel)
                .create();

        if (negativeText != null) {
            Button cancel = createDialogButton(negativeText, false);
            cancel.setOnClickListener(v -> dialog.dismiss());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dp(46), 1f);
            lp.rightMargin = dp(12);
            buttons.addView(cancel, lp);
        }

        Button positive = createDialogButton(positiveText, true);
        positive.setOnClickListener(v -> {
            dialog.dismiss();
            if (onPositive != null) {
                onPositive.run();
            }
        });
        buttons.addView(positive, new LinearLayout.LayoutParams(0, dp(46), 1f));
        panel.addView(buttons, matchWrap());

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(windowWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private Button createDialogButton(String text, boolean filled) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setAllCaps(false);
        button.setTextColor(filled ? 0xFFFFFFFF : ACCENT_COLOR);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(filled ? ACCENT_COLOR : TONAL_COLOR);
        bg.setCornerRadius(dp(15));
        button.setBackground(bg);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        return button;
    }

    private TextView dialogMessage(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(TEXT_SECONDARY);
        view.setLineSpacing(dp(4), 1.05f);
        return view;
    }

    private LinearLayout createCard(int bgColor, int radius) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(bgColor);
        background.setCornerRadius(radius);
        card.setBackground(background);
        card.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.setLayoutParams(params);
        return card;
    }

    private void showContentDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        addSwitch(content, R.string.read_app_name, KEY_READ_APP_NAME, true);
        addSwitch(content, R.string.read_title, KEY_READ_TITLE, true);
        addSwitch(content, R.string.read_body, KEY_READ_BODY, true);
        showMiuixDialog(getString(R.string.section_switch), content, false,
                getString(android.R.string.ok), null, null);
    }

    private void showVoiceDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView rateLabel = addValueLabel(content);
        addSeekBar(content, KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE, 200, value ->
                rateLabel.setText(getString(R.string.speech_rate_label, value)));

        TextView volumeLabel = addValueLabel(content);
        addSeekBar(content, KEY_VOLUME_PERCENT, DEFAULT_VOLUME_PERCENT, 100, value ->
                volumeLabel.setText(getString(R.string.volume_label, value)));

        addSwitch(content, R.string.boost_volume_title, KEY_BOOST_VOLUME, true);
        addSwitch(content, R.string.beep_before_speech, KEY_BEEP, true);

        showMiuixDialog(getString(R.string.section_sound), content, false,
                getString(android.R.string.ok), null, null);
    }

    private void showWhitelistDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView desc = new TextView(this);
        desc.setText(R.string.allowed_packages_desc);
        desc.setTextSize(13);
        desc.setTextColor(TEXT_SECONDARY);
        desc.setLineSpacing(dp(3), 1.0f);
        content.addView(desc);

        EditText input = new EditText(this);
        input.setText(preferences.getString(KEY_ALLOWED_PACKAGES, ""));
        input.setHint(R.string.allowed_packages_hint);
        input.setTextSize(14);
        input.setTextColor(TEXT_PRIMARY);
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(0xFFF7F8FA);
        fieldBg.setCornerRadius(dp(14));
        input.setBackground(fieldBg);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.topMargin = dp(12);
        content.addView(input, inputParams);

        final AlertDialog[] holder = new AlertDialog[1];
        Button pickButton = createOutlineButton(getString(R.string.pick_apps_button), () -> {
            // 先把输入框里的内容落盘，避免选择回来后丢掉手改的包名
            preferences.edit()
                    .putString(KEY_ALLOWED_PACKAGES, input.getText().toString().trim())
                    .apply();
            // 必须先关掉这个弹窗，否则选择页返回后又会退回到它上面
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            openAppPicker();
        });
        LinearLayout.LayoutParams pickParams = matchWrap();
        pickParams.topMargin = dp(14);
        content.addView(pickButton, pickParams);

        holder[0] = showMiuixDialog(getString(R.string.section_allowlist), content, false,
                getString(R.string.save_allowed_packages), () -> {
                    preferences.edit()
                            .putString(KEY_ALLOWED_PACKAGES, input.getText().toString().trim())
                            .apply();
                    buildLayout();
                    Toast.makeText(this, R.string.allowed_packages_saved, Toast.LENGTH_SHORT).show();
                },
                getString(android.R.string.cancel));
    }

    private void showDndDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        addSwitch(content, R.string.dnd_enabled, KEY_DND_ENABLED, false);
        TextView label = addValueLabel(content);

        final SeekBar[] endHolder = new SeekBar[1];
        SeekBar startBar = addSeekBar(content, KEY_DND_START, 23, 23, value ->
                label.setText(getString(R.string.dnd_range_label,
                        String.format(java.util.Locale.getDefault(), "%02d:00", value),
                        String.format(java.util.Locale.getDefault(), "%02d:00",
                                endHolder[0] == null ? 7 : endHolder[0].getProgress()))));
        SeekBar endBar = addSeekBar(content, KEY_DND_END, 7, 23, value ->
                label.setText(getString(R.string.dnd_range_label,
                        String.format(java.util.Locale.getDefault(), "%02d:00",
                                startBar.getProgress()),
                        String.format(java.util.Locale.getDefault(), "%02d:00", value))));
        endHolder[0] = endBar;

        showMiuixDialog(getString(R.string.section_dnd), content, false,
                getString(android.R.string.ok), () -> {
                    // 入口行的「已开启 / 已关闭」是建树时读的值，不重建就一直显示旧的
                    buildLayout();
                }, null);
    }

    private void showAdvancedDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        // ---- 后台轮询 ----
        TextView pollLabel = addValueLabel(body);
        SeekBar pollBar = new SeekBar(this);
        // 非线性档位：小间隔调得精细，大间隔直接给省电选项
        final int[] pollSteps = {1000, 2000, 3000, 4000, 5000, 8000, 10000, 15000, 20000, 30000};
        pollBar.setMax(pollSteps.length - 1);
        int savedPoll = preferences.getInt(KEY_POLL_INTERVAL, DEFAULT_POLL_INTERVAL_MS);
        int initialIndex = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < pollSteps.length; i++) {
            int diff = Math.abs(pollSteps[i] - savedPoll);
            if (diff < bestDiff) {
                bestDiff = diff;
                initialIndex = i;
            }
        }
        pollBar.setProgress(initialIndex);
        pollLabel.setText(getString(R.string.poll_interval_label,
                pollSteps[initialIndex] / 1000f));
        pollBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int index = Math.max(0, Math.min(pollSteps.length - 1, progress));
                int millis = pollSteps[index];
                pollLabel.setText(getString(R.string.poll_interval_label, millis / 1000f));
                if (fromUser) {
                    preferences.edit().putInt(KEY_POLL_INTERVAL, millis).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        body.addView(pollBar, matchWrap());

        TextView pollHint = new TextView(this);
        pollHint.setText("未连接耳机时完全停掉轮询（只保留 2 分钟空闲心跳）；检测到系统回调恢复正常后自动降频。");
        pollHint.setTextSize(12);
        pollHint.setTextColor(TEXT_TERTIARY);
        pollHint.setPadding(0, dp(4), 0, dp(8));
        body.addView(pollHint);

        // ---- 诊断 ----
        body.addView(createSectionTitle("故障诊断"));
        TextView diagDesc = new TextView(this);
        diagDesc.setText("收不到播报时先跑诊断：检查权限、蓝牙、白名单和服务状态，"
                + "并列出每条通知被跳过的原因。");
        diagDesc.setTextSize(12.5f);
        diagDesc.setTextColor(TEXT_SECONDARY);
        diagDesc.setLineSpacing(dp(3), 1.0f);
        diagDesc.setPadding(0, dp(6), 0, dp(10));
        body.addView(diagDesc);
        body.addView(createOutlineButton("运行诊断", this::runDiagnostics), matchWrap());

        // ---- 防冻结守护 ----
        body.addView(createSectionTitle("防冻结守护（需 Root，可选）"));
        TextView guardText = new TextView(this);
        guardText.setText("部分 ROM（如一加/ColorOS）会周期性冻结进程，冻结期间应用无法自救，"
                + "需外部脚本定时解冻。");
        guardText.setTextSize(12.5f);
        guardText.setTextColor(TEXT_SECONDARY);
        guardText.setLineSpacing(dp(3), 1.0f);
        body.addView(guardText);

        TextView pathText = new TextView(this);
        pathText.setText("/data/adb/service.d/airannounce-thaw.sh");
        pathText.setTextSize(12.5f);
        pathText.setTextColor(TEXT_PRIMARY);
        GradientDrawable pathBg = new GradientDrawable();
        pathBg.setColor(0xFFF7F8FA);
        pathBg.setCornerRadius(dp(12));
        pathText.setBackground(pathBg);
        pathText.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams pathParams = matchWrap();
        pathParams.topMargin = dp(10);
        pathParams.bottomMargin = dp(8);
        body.addView(pathText, pathParams);

        TextView guardHint = new TextView(this);
        guardHint.setText("没有 Root 也能用，偶发不播报时请在系统设置里允许自启动、关闭电池优化。");
        guardHint.setTextSize(12);
        guardHint.setTextColor(TEXT_TERTIARY);
        guardHint.setLineSpacing(dp(3), 1.0f);
        body.addView(guardHint);

        LinearLayout.LayoutParams copyParams = matchWrap();
        copyParams.topMargin = dp(10);
        body.addView(createOutlineButton(getString(R.string.copy_uninstall_command), () -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "command", "rm /data/adb/service.d/airannounce-thaw.sh"));
                Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
            }
        }), copyParams);

        // ---- Shizuku 通道（无 Root 时的降级方案）----
        body.addView(createSectionTitle("Shizuku 通道（可选，无 Root 时用）"));
        TextView shizukuDesc = new TextView(this);
        shizukuDesc.setText("借 Shizuku 的 shell 权限补做三件事：重绑监听、standby 拉 active、"
                + "加入电池白名单。无法解冻被冻结的进程，仅作无 Root 设备的兜底。");
        shizukuDesc.setTextSize(12.5f);
        shizukuDesc.setTextColor(TEXT_SECONDARY);
        shizukuDesc.setLineSpacing(dp(3), 1.0f);
        body.addView(shizukuDesc);

        final TextView shizukuState = new TextView(this);
        shizukuState.setTextSize(12.5f);
        shizukuState.setTextColor(TEXT_TERTIARY);
        shizukuState.setPadding(0, dp(8), 0, dp(8));
        shizukuState.setText("正在检测 Shizuku…");
        body.addView(shizukuState);

        LinearLayout shizukuButtons = new LinearLayout(this);
        shizukuButtons.setOrientation(LinearLayout.HORIZONTAL);

        Button shizukuGrant = createOutlineButton("授权 Shizuku", null);
        shizukuGrant.setOnClickListener(v -> {
            // NOT_RUNNING 时这个按钮是「打开 Shizuku」，激活后再回来授权
            if (ShizukuChannel.State.NOT_RUNNING.equals(v.getTag())) {
                openShizukuApp();
                return;
            }
            if (!ShizukuChannel.requestPermission()) {
                Toast.makeText(this, "Shizuku 未运行：请先打开 Shizuku 完成激活",
                        Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams grantParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        grantParams.rightMargin = dp(10);
        shizukuButtons.addView(shizukuGrant, grantParams);

        Button shizukuApply = createOutlineButton("立即应用", null);
        shizukuApply.setOnClickListener(v -> {
            v.setEnabled(false);
            shizukuState.setText("正在通过 Shizuku 应用…");
            new Thread(() -> {
                final String report = ShizukuChannel.apply(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    shizukuState.setText(report);
                    v.setEnabled(true);
                    // 弹窗被提前关掉时报告就无声消失了，至少留一条 Toast
                    Toast.makeText(this, report.contains("\u2717")
                            ? "Shizuku：部分命令未生效" : "Shizuku：保活设置已应用",
                            Toast.LENGTH_SHORT).show();
                });
            }, "shizuku-apply").start();
        });
        shizukuButtons.addView(shizukuApply, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams shizukuButtonParams = matchWrap();
        shizukuButtonParams.topMargin = dp(10);
        body.addView(shizukuButtons, shizukuButtonParams);

        Switch shizukuSwitch = new Switch(this);
        shizukuSwitch.setText("自动应用（只做白名单与 standby bucket）");
        shizukuSwitch.setTextSize(15);
        shizukuSwitch.setTextColor(TEXT_PRIMARY);
        shizukuSwitch.setPadding(0, dp(14), 0, dp(4));
        shizukuSwitch.setChecked(preferences.getBoolean(ShizukuChannel.KEY_ENHANCE, false));
        shizukuSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(ShizukuChannel.KEY_ENHANCE, checked).apply();
            if (checked) {
                ShizukuChannel.applyQuietlyIfEnabled(this);
            }
        });
        body.addView(shizukuSwitch, matchWrap());

        refreshShizukuRow(shizukuState, shizukuGrant, shizukuApply);
        final AlertDialog[] advancedHolder = new AlertDialog[1];

        // ---- 关于 ----
        body.addView(createSectionTitle("关于"));
        body.addView(createAboutRow("版本", getVersionLabel()));
        body.addView(createAboutRow("运行环境", "Android 8.0+"));
        TextView privacy = new TextView(this);
        privacy.setText("不联网，通知内容仅在本机处理，不上传任何数据。");
        privacy.setTextSize(12);
        privacy.setTextColor(TEXT_TERTIARY);
        privacy.setLineSpacing(dp(3), 1.0f);
        privacy.setPadding(0, dp(8), 0, dp(8));
        body.addView(privacy);

        advancedHolder[0] = showMiuixDialog(getString(R.string.section_advanced), body, true,
                getString(android.R.string.ok), null, null);
        if (advancedHolder[0] != null) {
            advancedHolder[0].setOnDismissListener(d -> {
                // 弹窗没了就别再往里刷状态
                if (shizukuStateLabel == shizukuState) {
                    shizukuStateLabel = null;
                    shizukuGrantButton = null;
                    shizukuApplyButton = null;
                }
            });
        }
    }

    /** 后台查一次 Shizuku 状态，避免 Binder 调用卡住主线程。 */
    private void refreshShizukuRow(TextView label, Button grant, Button apply) {
        shizukuStateLabel = label;
        shizukuGrantButton = grant;
        shizukuApplyButton = apply;
        new Thread(() -> {
            final ShizukuChannel.State state = ShizukuChannel.state(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                boolean showGrant = state == ShizukuChannel.State.NOT_GRANTED
                        || state == ShizukuChannel.State.NOT_RUNNING;
                grant.setVisibility(showGrant ? View.VISIBLE : View.GONE);
                grant.setTag(state);
                grant.setText(state == ShizukuChannel.State.NOT_RUNNING
                        ? "打开 Shizuku" : "授权 Shizuku");
                boolean ready = state == ShizukuChannel.State.READY;
                apply.setEnabled(ready);
                // 自定义背景不会跟着 enabled 变色，手动降透明度避免看起来能点
                apply.setAlpha(ready ? 1f : 0.4f);
                String text = "状态：" + ShizukuChannel.describe(state);
                if (state == ShizukuChannel.State.READY) {
                    long last = preferences.getLong(ShizukuChannel.KEY_LAST_APPLIED, 0L);
                    if (last > 0L) {
                        text += "（上次应用 " + new java.text.SimpleDateFormat(
                                "MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(new java.util.Date(last)) + "）";
                    }
                }
                label.setText(text);
            });
        }, "shizuku-state").start();
    }

    /** 授权回来、或应用重新回到前台时，把弹窗里的 Shizuku 状态重查一遍。 */
    private void rerunShizukuRefresh() {
        TextView label = shizukuStateLabel;
        Button grant = shizukuGrantButton;
        Button apply = shizukuApplyButton;
        if (label != null && grant != null && apply != null) {
            refreshShizukuRow(label, grant, apply);
        }
    }

    private void openShizukuApp() {
        Intent intent = getPackageManager()
                .getLaunchIntentForPackage(ShizukuChannel.SHIZUKU_PACKAGE);
        if (intent == null) {
            Toast.makeText(this, "无法打开 Shizuku", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(intent);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "无法打开 Shizuku", Toast.LENGTH_SHORT).show();
        }
    }

    private String getVersionLabel() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception exception) {
            return "未知";
        }
    }

    private TextView createSectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(13);
        title.setTextColor(TEXT_TERTIARY);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(20), 0, dp(4));
        return title;
    }

    private View createAboutRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(14);
        name.setTextColor(TEXT_PRIMARY);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(13);
        val.setTextColor(TEXT_SECONDARY);
        row.addView(val);
        return row;
    }

    private Button createOutlineButton(String text, Runnable onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTextColor(ACCENT_COLOR);
        btn.setAllCaps(false);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TONAL_COLOR);
        bg.setCornerRadius(dp(14));
        btn.setBackground(bg);
        btn.setStateListAnimator(null);
        btn.setPadding(dp(14), dp(12), dp(14), dp(12));
        // onClick 传 null 表示稍后自行 setOnClickListener（需要拿到按钮自身时用）
        if (onClick != null) {
            btn.setOnClickListener(v -> onClick.run());
        }
        return btn;
    }

    // ---------------- 诊断 ----------------

    private void runDiagnostics() {
        StringBuilder report = new StringBuilder();
        report.append("耳机通知播报 · 诊断报告\n");
        report.append("时间：").append(new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()))
                .append("\n");
        report.append("版本：").append(getVersionLabel()).append("\n");
        report.append("\n【权限与状态】\n");

        boolean access = hasNotificationAccess();
        report.append(access ? "✓ 通知使用权：已授权\n" : "✗ 通知使用权：未授权（这是最常见原因）\n");

        int sensitiveState = sensitiveNotificationState();
        if (sensitiveState == 1) {
            report.append("✓ 敏感通知：已允许\n");
        } else if (sensitiveState == -1) {
            report.append("✗ 敏感通知：被禁止（Android 15+ 聊天通知读不到）\n");
        } else {
            report.append("· 敏感通知：未检测到限制（若聊天通知仍无声，请到系统设置手动确认）\n");
        }

        boolean postNotif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        report.append(postNotif ? "✓ 通知权限：已授予\n" : "✗ 通知权限：未授予\n");

        boolean bt = hasBluetoothAudioOutput(this);
        report.append(bt ? "✓ 蓝牙音频：已连接\n" : "✗ 蓝牙音频：未连接（未连接时不会播报）\n");

        boolean battery = isIgnoringBatteryOptimizations();
        report.append(battery ? "✓ 电池优化：已豁免\n" : "✗ 电池优化：未豁免（后台可能被限制）\n");

        boolean enabled = preferences.getBoolean(KEY_ENABLED, true);
        report.append(enabled ? "✓ 播报开关：开启\n" : "✗ 播报开关：关闭\n");

        boolean dnd = preferences.getBoolean(KEY_DND_ENABLED, false);
        report.append(dnd ? "⚠ 勿扰时段：开启中（该时段内不播报）\n" : "· 勿扰时段：关闭\n");

        String allowed = preferences.getString(KEY_ALLOWED_PACKAGES, "");
        if (allowed == null || allowed.trim().isEmpty()) {
            report.append("· 白名单：空，播报所有应用\n");
        } else {
            report.append("· 白名单：").append(allowed.trim()).append("\n");
        }

        boolean serviceRunning = NotificationReaderService.isServiceRunning();
        report.append(serviceRunning ? "✓ 监听服务：运行中\n" : "✗ 监听服务：未运行（尝试重新打开应用）\n");

        report.append("\n【运行时状态】\n");
        report.append(NotificationReaderService.describeRuntime()).append("\n");

        report.append("\n【进程存活情况】\n");
        String interruptions = NotificationReaderService.getInterruptionLog(this);
        if (interruptions == null || interruptions.trim().isEmpty()) {
            report.append("· 未发现服务中断（进程一直在运行）\n");
        } else {
            report.append("⚠ 检测到服务中断（这段时间的通知不会被播报）：\n");
            for (String line : interruptions.split("\n")) {
                if (!line.trim().isEmpty()) {
                    report.append("  ").append(line.trim()).append("\n");
                }
            }
        }
        report.append("说明：进程被强制结束时无法留下日志，这里靠心跳断档反推，"
                + "设备长时间休眠也可能被记为中断。\n");

        report.append("\n【最近处理记录】\n");
        java.util.List<String> events = NotificationReaderService.getRecentEvents();
        int callbackCount = 0;
        int pollCount = 0;
        if (events.isEmpty()) {
            report.append("（暂无记录。收到通知后会在这里显示处理结果）\n");
        } else {
            for (String event : events) {
                report.append(event).append("\n");
                if (event.contains("系统回调")) {
                    callbackCount++;
                } else if (event.contains("轮询补获")) {
                    pollCount++;
                }
            }
        }

        report.append("\n【怎么读这份记录】\n");
        report.append("系统回调 ").append(callbackCount)
                .append(" 条，轮询补获 ").append(pollCount).append(" 条\n");
        if (callbackCount > 0) {
            report.append("· 有「系统回调」说明系统在正常投递通知，漏报原因大概率在过滤规则，"
                    + "对照上面的「跳过」记录即可。\n");
        } else if (pollCount > 0) {
            report.append("· 只有「轮询补获」没有「系统回调」，说明系统回调没送达，"
                    + "通常是应用被系统冻结或限制后台，检查电池优化、自启动、防冻结守护。\n");
        } else if (events.isEmpty()) {
            report.append("· 一条记录都没有：通知根本没有到达本应用。先确认通知使用权真的开启、"
                    + "Android 15+ 的敏感通知是否允许，再看白名单有没有把目标应用排除。\n");
        }
        report.append("· 如果目标应用的通知完全没出现在记录里，而其他应用有，"
                + "多半是该应用穿了「私密/敏感通知」，需要在系统设置里单独放行。\n");

        String text = report.toString();

        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(TEXT_PRIMARY);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setLineSpacing(dp(2), 1.0f);
        view.setTextIsSelectable(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(view);

        Button clearButton = createOutlineButton("清空记录", () -> {
            NotificationReaderService.clearRecentEvents();
            NotificationReaderService.clearInterruptionLog(this);
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams clearParams = matchWrap();
        clearParams.topMargin = dp(14);
        content.addView(clearButton, clearParams);

        showMiuixDialog("诊断结果", content, true, "复制", () -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("diag", text));
                Toast.makeText(this, "诊断结果已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        }, getString(android.R.string.cancel));
    }

    /** 1=允许，-1=禁止，0=未检测到限制或无法判断。 */
    private int sensitiveNotificationState() {
        if (Build.VERSION.SDK_INT < 35) {
            return 1;
        }
        // Android 15+ 的判定入口在公开 SDK 里不一定可见（本机 compileSdk 35 的
        // android.jar 就没有该常量），各代 ROM 的 op 名也不一致，这里三层兜底：
        //   1. 官方函数 canUseNotificationFunction
        //   2. 常见字符串 op 名（"android:xxx" 形式）
        //   3. getOpByName 拿数字编号再查（hidden API，失败就算了）
        // 全部失败返回 0（无法判断），界面按「检测受限」提示，不再断言未开启。
        Boolean byFunction = checkSensitiveByNotificationFunction();
        if (byFunction != null) {
            return byFunction ? 1 : -1;
        }
        android.app.AppOpsManager ops = (android.app.AppOpsManager)
                getSystemService(APP_OPS_SERVICE);
        if (ops == null) {
            return 0;
        }
        // 之前这里只写了 "android:receive_sensitive_notification"，
        // 在本机（ColorOS / Android 15）对不上，检测永远失败，
        // 界面就会一直显示「需开启」——即使 appops 里其实是 allow。
        String[] opNames = {
                "android:receive_sensitive_notification",
                "android:receive_sensitive_notifications",
                "android:access_internally_sent_notifications",
        };
        for (String opName : opNames) {
            Integer state = stateOfMode(unsafeMode(ops, opName));
            if (state != null) {
                return state;
            }
        }
        Integer op = opByName("RECEIVE_SENSITIVE_NOTIFICATIONS");
        if (op != null) {
            try {
                java.lang.reflect.Method method = android.app.AppOpsManager.class
                        .getMethod("unsafeCheckOpNoThrow", int.class, int.class, String.class);
                Object value = method.invoke(ops, op, android.os.Process.myUid(), getPackageName());
                Integer state = stateOfMode(value instanceof Integer ? (Integer) value : null);
                if (state != null) {
                    return state;
                }
            } catch (Throwable ignored) {
                // hidden API 被拦或该 ROM 没有这个方法
            }
        }
        return 0;
    }

    private Integer unsafeMode(android.app.AppOpsManager ops, String opName) {
        try {
            return ops.unsafeCheckOpNoThrow(opName, android.os.Process.myUid(), getPackageName());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer stateOfMode(Integer mode) {
        if (mode == null) {
            return null;
        }
        if (mode == android.app.AppOpsManager.MODE_ALLOWED) {
            return 1;
        }
        if (mode == android.app.AppOpsManager.MODE_IGNORED
                || mode == android.app.AppOpsManager.MODE_ERRORED) {
            return -1;
        }
        return null;
    }

    private Integer opByName(String name) {
        try {
            java.lang.reflect.Method method = android.app.AppOpsManager.class
                    .getMethod("getOpByName", String.class);
            Object value = method.invoke(null, name);
            if (value instanceof Integer && (Integer) value >= 0) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 反射问 NotificationManager#canUseNotificationFunction，拿不到就返回 null。 */
    private Boolean checkSensitiveByNotificationFunction() {
        try {
            android.app.NotificationManager manager = (android.app.NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) {
                return null;
            }
            java.lang.reflect.Method method = android.app.NotificationManager.class
                    .getMethod("canUseNotificationFunction", String.class);
            Object result = method.invoke(manager,
                    "android.access_internally_sent_notifications");
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (Throwable throwable) {
            android.util.Log.w("AirAnnounce",
                    "canUseNotificationFunction not reachable", throwable);
            return null;
        }
    }

    /** 按钮写着「去开启」就要真的跳过去；跳转不可用才退回文字说明。 */
    /**
     * 敏感通知开关藏在「通知使用权 → 本应用」的详情页里。
     * 优先用官方 deep link 直达（API 30+），失败再退回列表页并高亮本应用条目；
     * 之前只跳列表页，用户还得自己翻，点了像没反应。
     */
    private void openSensitiveNotificationSettings() {
        String component = new ComponentName(this, NotificationReaderService.class)
                .flattenToString();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                detail.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component);
                if (detail.resolveActivity(getPackageManager()) != null) {
                    startActivity(detail);
                    Toast.makeText(this, "在这一页找到「敏感通知」并允许", Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (RuntimeException exception) {
                android.util.Log.w("AirAnnounce", "open detail settings failed", exception);
            }
        }
        try {
            Bundle fragmentArgs = new Bundle();
            fragmentArgs.putString(":settings:fragment_args_key", component);
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.putExtra(":settings:fragment_args_key", component);
            intent.putExtra(":settings:show_fragment_args", fragmentArgs);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                Toast.makeText(this, R.string.guide_sensitive_hint, Toast.LENGTH_LONG).show();
                return;
            }
        } catch (RuntimeException exception) {
            android.util.Log.w("AirAnnounce", "open notification access settings failed", exception);
        }
        Toast.makeText(this, R.string.notification_settings_unavailable, Toast.LENGTH_LONG).show();
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        } else {
            Toast.makeText(this, R.string.notification_settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void speakTestMessage() {
        if (testTts == null) {
            // 首次点击：拉起引擎，初始化完成后自动把这句话念出来
            pendingSpeakAfterInit = true;
            Toast.makeText(this, "正在启动语音引擎…", Toast.LENGTH_SHORT).show();
            initializeTestTts();
            return;
        }
        if (!testTtsReady) {
            Toast.makeText(this, R.string.tts_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        if (preferences.getBoolean(KEY_BOOST_VOLUME, true)) {
            applyTestVolumeBoost();
        }
        android.os.Bundle params = new android.os.Bundle();
        String testText = TextFormatter.expandVerificationCodes(getString(R.string.test_message));
        testTts.speak(testText, TextToSpeech.QUEUE_FLUSH, params, "test");
    }

    private void applyTestVolumeBoost() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        int percent = preferences.getInt(KEY_VOLUME_PERCENT, DEFAULT_VOLUME_PERCENT);
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int target = Math.round(max * percent / 100f);
        if (target <= current) {
            return;
        }
        testSavedVolume = current;
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        } catch (RuntimeException exception) {
            testSavedVolume = -1;
            return;
        }
        // 播报结束由 TTS 回调触发恢复；这里只做超时兜底，避免引擎不回调时音量卡住
        uiHandler.postDelayed(this::restoreTestVolume, 30000L);
    }

    /**
     * 按「播报开关 + 蓝牙连接」同步保活服务。
     * 主界面开关和快捷磁贴共用这一处逻辑，避免关掉播报后还挂着前台服务通知。
     */
    static void syncKeepAlive(Context context) {
        boolean enabled = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
        if (!enabled) {
            KeepAliveService.stop(context);
            // 关开关必须同时闭嘴：队列里最多还压着 5 条、引擎里还有已排队的
            // utterance，不清的话「关了还在念」能持续几十秒
            NotificationReaderService.stopEverything(context);
        } else if (hasBluetoothAudioOutput(context)) {
            KeepAliveService.start(context);
        }
        // 轮询跟着「开关 + 耳机」走：关掉播报或拔了耳机就该停止空转
        NotificationReaderService.syncPollingIfAvailable();
    }

    private void updateStatus() {
        boolean accessGranted = hasNotificationAccess();
        boolean enabled = preferences.getBoolean(KEY_ENABLED, true);
        boolean connected = hasBluetoothAudioOutput(this);
        if (enabledSwitch != null) {
            if (enabledSwitch.isChecked() != enabled) {
                // 磁贴改过开关：先摘监听再改状态，避免回环触发写盘
                enabledSwitch.setOnCheckedChangeListener(null);
                enabledSwitch.setChecked(enabled);
                enabledSwitch.setOnCheckedChangeListener(enabledSwitchListener);
            }
            enabledSwitch.setEnabled(accessGranted);
        }
        if (heroDeviceView != null) {
            heroDeviceView.setText(connected ? "蓝牙耳机已连接" : "未连接蓝牙音频");
        }
        if (heroStateView != null && heroStateChipBg != null) {
            String text;
            int bgColor;
            int fgColor;
            if (!accessGranted) {
                text = "需要通知权限";
                bgColor = 0xFFFFF3E6;
                fgColor = 0xFFE8890C;
            } else if (!enabled) {
                text = "已暂停";
                bgColor = BG_COLOR;
                fgColor = 0xFF8A8A8A;
            } else if (connected) {
                text = "运行中";
                bgColor = 0xFFE6F7EE;
                fgColor = 0xFF12A05A;
            } else {
                text = "等待耳机";
                bgColor = TONAL_COLOR;
                fgColor = ACCENT_COLOR;
            }
            heroStateView.setText(text);
            heroStateChipBg.setColor(bgColor);
            heroStateView.setTextColor(fgColor);
        }
    }

    private boolean hasNotificationAccess() {
        return hasNotificationAccess(this);
    }

    /** 磁贴等组件没有 Activity 上下文，需要静态版本判断授权状态。 */
    static boolean hasNotificationAccess(Context context) {
        String enabledListeners = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (enabledListeners == null) {
            return false;
        }
        String component = new ComponentName(context, NotificationReaderService.class)
                .flattenToString();
        for (String listener : enabledListeners.split(":")) {
            if (component.equalsIgnoreCase(listener)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasBluetoothAudioOutput(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            return false;
        }
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                    return true;
                default:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            && (device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET
                            || device.getType() == AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
                        return true;
                    }
                    break;
            }
        }
        return false;
    }

    // ---------------- 权限 ----------------

    private boolean requestRuntimePermissionsIfNeeded() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return true;
        }
        return false;
    }

    private void runPermissionAudit() {
        // Activity 已销毁时再弹窗会抛 WindowManager$BadTokenException
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // 首启会同时排两个审计（600ms 延迟 + 权限回调），避免叠两个弹窗
        if (auditDialog != null && auditDialog.isShowing()) {
            return;
        }
        boolean needListener = !hasNotificationAccess();
        boolean needPostNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        boolean needBluetooth = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED;
        boolean needBattery = !isIgnoringBatteryOptimizations();

        StringBuilder missing = new StringBuilder();
        if (needListener) {
            missing.append(getString(R.string.guide_item_listener)).append('\n');
        }
        if (needPostNotif) {
            missing.append(getString(R.string.guide_item_post_notification)).append('\n');
        }
        if (needBluetooth) {
            missing.append(getString(R.string.guide_item_bluetooth)).append('\n');
        }
        if (needBattery) {
            missing.append(getString(R.string.guide_item_battery)).append('\n');
        }

        String sensitiveHint = getString(R.string.guide_sensitive_hint);

        if (missing.length() == 0) {
            auditDialog = showMiuixDialog(getString(R.string.guide_all_ready_title),
                    dialogMessage(sensitiveHint), false,
                    getString(android.R.string.ok), null, null);
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(dialogMessage(
                getString(R.string.guide_message, missing.toString(), sensitiveHint)));

        Button retry = createOutlineButton(getString(R.string.guide_retry), () -> {
            // 先关掉当前弹窗：否则开头的「已有弹窗在显示」守卫会把自己拦掉，
            // 用户去系统设置授权回来点「重新检查」就是一次次毫无反应
            if (auditDialog != null) {
                auditDialog.dismiss();
            }
            auditDialog = null;
            runPermissionAudit();
        });
        LinearLayout.LayoutParams retryParams = matchWrap();
        retryParams.topMargin = dp(14);
        content.addView(retry, retryParams);

        auditDialog = showMiuixDialog(getString(R.string.guide_title), content, true,
                getString(R.string.guide_go_settings), () -> {
                    // 之前只处理了通知使用权和电池两项：只缺通知权限 / 蓝牙权限时
                    // 点「去设置」会把弹窗关掉却什么都不做。
                    if (needListener) {
                        openNotificationSettings();
                    } else if (needPostNotif || needBluetooth) {
                        requestRuntimePermissionsIfNeeded();
                    } else if (needBattery) {
                        requestIgnoreBatteryOptimizations();
                    }
                },
                getString(android.R.string.cancel));
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (RuntimeException exception) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                Toast.makeText(this, R.string.notification_settings_unavailable,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------------- 弹窗内的小控件 ----------------

    private interface OnValueChanged {
        void onChanged(int value);
    }

    private Switch addSwitch(LinearLayout parent, int textRes, String key, boolean defValue) {
        Switch toggle = new Switch(this);
        toggle.setText(textRes);
        toggle.setTextSize(15);
        toggle.setTextColor(TEXT_PRIMARY);
        toggle.setPadding(0, dp(10), 0, dp(10));
        toggle.setChecked(preferences.getBoolean(key, defValue));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            if (KEY_ENABLED.equals(key)) {
                syncKeepAlive(this);
            }
        });
        parent.addView(toggle, matchWrap());
        return toggle;
    }

    private TextView addValueLabel(LinearLayout parent) {
        TextView label = new TextView(this);
        label.setTextSize(13);
        label.setTextColor(0xFF354754);
        label.setPadding(0, dp(10), 0, dp(2));
        parent.addView(label, matchWrap());
        return label;
    }

    private SeekBar addSeekBar(LinearLayout parent, String key, int defValue, int max,
            OnValueChanged listener) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(preferences.getInt(key, defValue));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT_COLOR));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT_COLOR));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (listener != null) {
                    listener.onChanged(progress);
                }
                if (fromUser) {
                    preferences.edit().putInt(key, progress).apply();
                    // 语速改了要立刻生效，否则要等 TTS 引擎下次重新初始化
                    if (KEY_SPEECH_RATE.equals(key)) {
                        NotificationReaderService.applySpeechRateIfReady();
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        parent.addView(bar, matchWrap());
        if (listener != null) {
            listener.onChanged(bar.getProgress());
        }
        return bar;
    }

    /** 测试播报结束（含被打断、被 mid-exit）后把媒体音量放回去。 */
    private void restoreTestVolume() {
        if (testSavedVolume < 0) {
            return;
        }
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, testSavedVolume, 0);
            } catch (RuntimeException ignored) {
            }
        }
        testSavedVolume = -1;
    }

    private void initializeTestTts() {
        testTts = new TextToSpeech(this, status -> {
            testTtsReady = status == TextToSpeech.SUCCESS;
            if (testTtsReady) {
                testTts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                testTts.setSpeechRate(Math.max(0.5f,
                        preferences.getInt(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE) / 100f));
                if (pendingSpeakAfterInit) {
                    pendingSpeakAfterInit = false;
                    speakTestMessage();
                }
                return;
            }
            // 失败后把实例回收掉，否则下次点「测试语音」永远提示「引擎仍在启动」
            TextToSpeech broken = testTts;
            testTts = null;
            pendingSpeakAfterInit = false;
            if (broken != null) {
                try {
                    broken.shutdown();
                } catch (RuntimeException ignored) {
                }
            }
            Toast.makeText(this, R.string.tts_init_failed, Toast.LENGTH_LONG).show();
        });
        testTts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                runOnUiThread(MainActivity.this::restoreTestVolume);
            }

            @Override
            public void onError(String utteranceId) {
                runOnUiThread(MainActivity.this::restoreTestVolume);
            }

            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                // 被打断（来电 / 别人抢焦点）时系统只回调 onStop，
                // 不处理的话媒体音量要等 30 秒兜底才恢复
                runOnUiThread(MainActivity.this::restoreTestVolume);
            }
        });
    }

    private void openAppPicker() {
        Intent intent = new Intent(this, AppPickerActivity.class);
        intent.putExtra(AppPickerActivity.EXTRA_SELECTED_PACKAGES,
                preferences.getString(KEY_ALLOWED_PACKAGES, ""));
        startActivityForResult(intent, REQUEST_PICK_APPS);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
