package com.sakura.airannounce;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用选择页：列出已安装应用，勾选后作为播报白名单。
 */
public class AppPickerActivity extends Activity {
    static final String EXTRA_SELECTED_PACKAGES = "selected_packages";
    static final String RESULT_PACKAGES = "packages";

    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> filteredApps = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText searchInput;
    private AppListAdapter adapter;
    private TextView emptyView;
    private TextView countView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String initial = getIntent().getStringExtra(EXTRA_SELECTED_PACKAGES);
        if (initial != null) {
            for (String entry : initial.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    selected.add(trimmed.toLowerCase(Locale.ROOT));
                }
            }
        }
        buildLayout();
        loadAppsAsync();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F3F5);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(16), dp(20), dp(8));

        TextView title = new TextView(this);
        title.setText(R.string.picker_title);
        title.setTextSize(20);
        title.setTextColor(0xFF191919);
        header.addView(title);

        TextView desc = new TextView(this);
        desc.setText(R.string.picker_desc);
        desc.setTextSize(13);
        desc.setTextColor(0xFF6B6B6B);
        desc.setPadding(0, dp(4), 0, dp(8));
        header.addView(desc);

        EditText search = new EditText(this);
        search.setHint(R.string.picker_search_hint);
        search.setTextSize(15);
        search.setTextColor(0xFF191919);
        search.setSingleLine(true);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(0xFFF7F8FA);
        searchBg.setCornerRadius(dp(14));
        search.setBackground(searchBg);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        header.addView(search, matchWrap());

        countView = new TextView(this);
        countView.setTextSize(13);
        countView.setTextColor(0xFF3482FF);
        countView.setPadding(0, dp(6), 0, 0);
        header.addView(countView);

        searchInput = search;
        root.addView(header, matchWrap());

        ListView listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        adapter = new AppListAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry entry = filteredApps.get(position);
            String key = entry.packageName.toLowerCase(Locale.ROOT);
            if (!selected.remove(key)) {
                selected.add(key);
            }
            updateCount();
            adapter.notifyDataSetChanged();
        });
        emptyView = new TextView(this);
        emptyView.setText(R.string.picker_loading);
        emptyView.setTextSize(15);
        emptyView.setTextColor(0xFF6B6B6B);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(40), 0, 0);

        // emptyView 必须和列表同层叠加：原来加在底部按钮栏之后，
        // 被 weight=1 的列表挤到屏幕外，加载/空态提示永远看不见。
        FrameLayout listContainer = new FrameLayout(this);
        listContainer.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listContainer.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(listContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button saveButton = createPickerButton(getString(R.string.picker_save), true);
        saveButton.setOnClickListener(view -> {
            // 不能用 allApps 过滤：列表会跳过无启动图标的系统应用，
            // 手填的包名也不在列表里。按用户实际勾选/填写的集合输出，
            // 否则保存一次就会把白名单里「看不见的」条目静默删掉。
            java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
            for (AppEntry entry : allApps) {
                String key = entry.packageName.toLowerCase(Locale.ROOT);
                if (selected.contains(key)) {
                    ordered.add(entry.packageName);
                }
            }
            for (String kept : selected) {
                boolean listed = false;
                for (String existing : ordered) {
                    if (existing.equalsIgnoreCase(kept)) {
                        listed = true;
                        break;
                    }
                }
                if (!listed) {
                    ordered.add(kept);
                }
            }
            StringBuilder builder = new StringBuilder();
            for (String packageName : ordered) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(packageName);
            }
            Intent result = new Intent();
            result.putExtra(RESULT_PACKAGES, builder.toString());
            setResult(RESULT_OK, result);
            finish();
        });

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setBackgroundColor(0xFFFFFFFF);
        bottom.setPadding(dp(16), dp(12), dp(16), dp(18));

        Button cancelButton = createPickerButton(getString(R.string.picker_cancel), false);
        cancelButton.setOnClickListener(view -> finish());

        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelParams.rightMargin = dp(12);
        bottom.addView(cancelButton, cancelParams);
        bottom.addView(saveButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(bottom, matchWrap());

        setContentView(root);
        updateCount();
    }

    private void loadAppsAsync() {
        new Thread(() -> {
            PackageManager packageManager = getPackageManager();
            List<ApplicationInfo> installed = packageManager.getInstalledApplications(0);
            List<AppEntry> loaded = new ArrayList<>();
            String self = getPackageName();
            for (ApplicationInfo info : installed) {
                if (self.equals(info.packageName)) {
                    continue;
                }
                // 跳过没有启动入口、也不发通知的系统组件，避免列表过长
                boolean hasLauncher = packageManager.getLaunchIntentForPackage(info.packageName) != null;
                boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (!hasLauncher && isSystem) {
                    continue;
                }
                AppEntry entry = new AppEntry();
                entry.packageName = info.packageName;
                entry.label = String.valueOf(info.loadLabel(packageManager));
                entry.icon = info.loadIcon(packageManager);
                loaded.add(entry);
            }
            Collator collator = Collator.getInstance(Locale.CHINA);
            Collections.sort(loaded, (a, b) -> collator.compare(a.label, b.label));

            mainHandler.post(() -> {
                // 页面已经关了就别再回填，避免持有已销毁 Activity 的 View
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                allApps.clear();
                allApps.addAll(loaded);
                // 列表是后台加载的，期间用户可能已经输入了关键词；
                // 固定按空串过滤会把结果弹回全量，看起来就像搜索失灵
                applyFilter(searchInput == null ? "" : searchInput.getText().toString());
            });
        }).start();
    }

    private void applyFilter(String keyword) {
        String lower = keyword.trim().toLowerCase(Locale.ROOT);
        filteredApps.clear();
        for (AppEntry entry : allApps) {
            if (lower.isEmpty()
                    || entry.label.toLowerCase(Locale.ROOT).contains(lower)
                    || entry.packageName.toLowerCase(Locale.ROOT).contains(lower)) {
                filteredApps.add(entry);
            }
        }
        emptyView.setVisibility(allApps.isEmpty() ? View.VISIBLE : View.GONE);
        if (!allApps.isEmpty()) {
            emptyView.setText(R.string.picker_empty);
            emptyView.setVisibility(filteredApps.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateCount();
    }

    private void updateCount() {
        if (countView != null) {
            countView.setText(getString(R.string.picker_selected_count, selected.size()));
        }
    }

    private Button createPickerButton(String text, boolean filled) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setAllCaps(false);
        button.setTextColor(filled ? 0xFFFFFFFF : 0xFF3482FF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(filled ? 0xFF3482FF : 0xFFEDF3FF);
        bg.setCornerRadius(dp(16));
        button.setBackground(bg);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, dp(13), 0, dp(13));
        button.setStateListAnimator(null);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class AppEntry {
        String label;
        String packageName;
        Drawable icon;
    }

    private final class AppListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filteredApps.size();
        }

        @Override
        public Object getItem(int position) {
            return filteredApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(AppPickerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(20), dp(10), dp(20), dp(10));
                row.setBackgroundColor(0xFFFFFFFF);

                ImageView icon = new ImageView(AppPickerActivity.this);
                LinearLayout.LayoutParams iconParams =
                        new LinearLayout.LayoutParams(dp(40), dp(40));
                iconParams.gravity = Gravity.CENTER_VERTICAL;
                row.addView(icon, iconParams);

                LinearLayout texts = new LinearLayout(AppPickerActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textsParams =
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                textsParams.setMarginStart(dp(14));
                textsParams.gravity = Gravity.CENTER_VERTICAL;
                row.addView(texts, textsParams);

                TextView label = new TextView(AppPickerActivity.this);
                label.setTextSize(16);
                label.setTextColor(0xFF191919);
                texts.addView(label);

                TextView packageName = new TextView(AppPickerActivity.this);
                packageName.setTextSize(12);
                packageName.setTextColor(0xFF9A9A9A);
                texts.addView(packageName);

                CheckBox checkBox = new CheckBox(AppPickerActivity.this);
                checkBox.setClickable(false);
                checkBox.setFocusable(false);
                LinearLayout.LayoutParams cbParams =
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                cbParams.gravity = Gravity.CENTER_VERTICAL;
                row.addView(checkBox, cbParams);

                holder = new ViewHolder();
                holder.icon = icon;
                holder.label = label;
                holder.packageName = packageName;
                holder.checkBox = checkBox;
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            AppEntry entry = filteredApps.get(position);
            holder.icon.setImageDrawable(entry.icon);
            holder.label.setText(entry.label);
            holder.packageName.setText(entry.packageName);
            holder.checkBox.setChecked(
                    selected.contains(entry.packageName.toLowerCase(Locale.ROOT)));
            return convertView;
        }
    }

    private static final class ViewHolder {
        ImageView icon;
        TextView label;
        TextView packageName;
        CheckBox checkBox;
    }
}
