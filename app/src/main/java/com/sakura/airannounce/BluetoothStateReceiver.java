package com.sakura.airannounce;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 监听蓝牙连接相关的系统广播。
 *
 * 连接：拉起前台保活服务（防止被厂商 ROM 冻结）并主动请求 NotificationListenerService
 *      重新绑定，确保耳机一连上就能开始工作。
 * 断开：停止前台服务，通知随之消失，不长期占用状态栏。
 */
public class BluetoothStateReceiver extends BroadcastReceiver {
    private static final String TAG = "AirAnnounce";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        // Android 12+ 用户拒绝 BLUETOOTH_CONNECT 时，读取 EXTRA_DEVICE / getAddress /
        // getBluetoothClass 会抛 SecurityException；部分 ROM 授权后也会偶发。
        // 广播接收器里异常不兜底就是一次崩溃弹窗，这里只记录不抛出。
        try {
            handleAction(context, intent);
        } catch (Throwable throwable) {
            Log.w(TAG, "Bluetooth broadcast handling failed: " + intent.getAction(), throwable);
        }
    }

    private void handleAction(Context context, Intent intent) {
        String action = intent.getAction();
        switch (action) {
            case BluetoothDevice.ACTION_ACL_CONNECTED: {
                // ACL 对任何蓝牙设备都会发：连键盘、手表、车机也曾拉起前台服务，
                // 状态栏挂着「运行中」但其实根本没有可播报的音频输出。
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                Log.i(TAG, "Bluetooth ACL connected: "
                        + (device == null ? "unknown" : device.getAddress()));
                if (isAudioDevice(device)) {
                    onAudioConnected(context);
                } else {
                    Log.i(TAG, "ACL device is not audio class; ignore.");
                }
                break;
            }

            case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                Log.i(TAG, "Bluetooth ACL disconnected broadcast.");
                handlePossibleDisconnect(context);
                break;

            case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED: {
                // A2DP 的 EXTRA_STATE 用 BluetoothProfile.STATE_*（0 / 2）
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                Log.i(TAG, action + " state=" + state);
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    onAudioConnected(context);
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    handlePossibleDisconnect(context);
                }
                break;
            }

            case BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED: {
                // 耳机音频状态用的是 BluetoothHeadset.STATE_AUDIO_*（10 / 12），
                // 之前错拿 BluetoothProfile.STATE_* 比较，这个分支永远命中不了。
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                Log.i(TAG, action + " state=" + state);
                if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                    onAudioConnected(context);
                } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    handlePossibleDisconnect(context);
                }
                break;
            }

            default:
                break;
        }
    }

    /** 只放行音频/电话大类设备（耳机、音箱、车载娱乐系统通常归在 AUDIO_VIDEO）。 */
    private boolean isAudioDevice(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        BluetoothClass bluetoothClass = device.getBluetoothClass();
        if (bluetoothClass == null) {
            return false;
        }
        int major = bluetoothClass.getMajorDeviceClass();
        return major == BluetoothClass.Device.Major.AUDIO_VIDEO
                || major == BluetoothClass.Device.Major.PHONE;
    }

    private void onAudioConnected(Context context) {
        // 播报被关掉时不需要常驻保活，避免留下一个没用的前台服务通知。
        boolean enabled = context.getSharedPreferences(
                        MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_ENABLED, true);
        if (!enabled) {
            Log.i(TAG, "Announcements disabled; skipping keep-alive.");
            return;
        }
        KeepAliveService.start(context);
        NotificationReaderService.requestRebindIfNeeded(context);
    }

    /**
     * ACL 断开不等于音频设备全部断开（可能还连着别的耳机），所以这里再确认一次。
     */
    private void handlePossibleDisconnect(Context context) {
        if (!MainActivity.hasBluetoothAudioOutput(context)) {
            Log.i(TAG, "No Bluetooth audio output left; stopping keep-alive.");
            KeepAliveService.stop(context);
        }
    }
}
