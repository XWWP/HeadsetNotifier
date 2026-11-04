package com.sakura.airannounce;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * 快捷设置磁贴：一键开关播报，并显示当前状态。
 *
 * 注意：磁贴不是保活手段。TileService 只在快捷设置面板展开时被系统绑定，
 * 面板收起后随即解绑，进程不会因此常驻。真正的保活仍然依赖
 * KeepAliveService 前台服务加 root 侧的解冻守护脚本。
 */
public class AnnounceTileService extends TileService {
    private static final String TAG = "AirAnnounce";

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        if (!MainActivity.hasNotificationAccess(this)) {
            // 没有通知使用权时翻开关没有意义，直接把用户送到主界面做授权。
            Log.i(TAG, "Tile clicked without notification access; opening MainActivity.");
            openMainActivity();
            return;
        }

        SharedPreferences preferences =
                getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE);
        boolean enabled = !preferences.getBoolean(MainActivity.KEY_ENABLED, true);
        preferences.edit().putBoolean(MainActivity.KEY_ENABLED, enabled).apply();
        Log.i(TAG, "Tile toggled, announcements enabled=" + enabled);

        MainActivity.syncKeepAlive(this);
        updateTile();
    }

    @SuppressWarnings("deprecation")
    private void openMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(intent);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        boolean enabled = getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_ENABLED, true);

        if (!MainActivity.hasNotificationAccess(this)) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            applySubtitle(tile, getString(R.string.tile_state_no_access));
        } else if (enabled) {
            tile.setState(Tile.STATE_ACTIVE);
            applySubtitle(tile, getString(MainActivity.hasBluetoothAudioOutput(this)
                    ? R.string.tile_state_connected
                    : R.string.tile_state_waiting));
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            applySubtitle(tile, getString(R.string.tile_state_disabled));
        }

        tile.setLabel(getString(R.string.tile_label));
        tile.updateTile();
    }

    /** Tile.setSubtitle 从 Android 10 才有，低版本忽略即可。 */
    private void applySubtitle(Tile tile, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(text);
        }
    }
}
