package com.sakura.airannounce;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 播报期间临时提升音量，播完恢复。
 *
 * 四个必须守住的点：
 * 1. 改哪条流由播报实际使用的 {@link AudioAttributes} 决定。
 *    TTS 用 USAGE_ASSISTANT 时音量流是 STREAM_ASSISTANT，硬编码 STREAM_MUSIC 会「改音乐流、响助手流」。
 * 2. setStreamVolume 返回 void，ROM 静默丢弃写入也看不出，因此每次写入都读回校验。
 * 3. ColorOS 按设备分别存音量（earpiece / speaker / bt_a2dp 各有 index），
 *    播报期间设备集变化就放弃写回，免得写进另一台设备的槽位。
 * 4. 恢复前要求当前音量仍等于本模块写入的目标值：用户播报期间自己调过就尊重用户。
 */
final class VolumeBoost {

    private static final String TAG = "AirAnnounce";

    private final AudioManager audioManager;

    private int saved = -1;
    private int stream = -1;
    private int appliedTarget = -1;
    private int appliedMax = -1;
    private String appliedDevices;

    VolumeBoost(Context context) {
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** 有未恢复的提升时为 true。兜底恢复据此判断要不要动手。 */
    boolean isActive() {
        return saved >= 0;
    }

    /**
     * 按 percent 提升音量。只在目标高于当前时动手，绝不主动压低用户音量。
     *
     * @return true 表示写入生效并已登记，调用方需要安排恢复。
     */
    boolean boost(AudioAttributes attributes, int percent) {
        if (audioManager == null) {
            return false;
        }
        if (saved >= 0) {
            // 上一批还没恢复：沿用原基准，不把已抬高的音量当成新基准
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        int targetStream = resolveStream(attributes);
        int max = audioManager.getStreamMaxVolume(targetStream);
        if (max <= 0) {
            Log.w(TAG, "volume boost skipped: max=" + max + " stream=" + targetStream);
            return false;
        }
        int current = audioManager.getStreamVolume(targetStream);
        int target = Math.max(1, Math.round(max * percent / 100f));
        if (target <= current) {
            return false;
        }

        saved = current;
        stream = targetStream;
        appliedTarget = target;
        appliedMax = max;
        appliedDevices = devicesOf(targetStream);

        try {
            audioManager.setStreamVolume(targetStream, target, 0);
        } catch (RuntimeException exception) {
            Log.w(TAG, "volume boost failed", exception);
            reset();
            return false;
        }

        int readBack = audioManager.getStreamVolume(targetStream);
        if (readBack != target) {
            // 写入被丢弃：不留基准，避免后续在错误的数值上"恢复"
            Log.w(TAG, "volume boost dropped: want=" + target + " got=" + readBack
                    + " stream=" + targetStream);
            reset();
            return false;
        }

        Log.i(TAG, "volume boosted " + current + " -> " + target
                + " (max " + max + ", stream " + targetStream + ")");
        return true;
    }

    /** 恢复原音量。用户中途改过、设备换过、档位上限变过都放弃写回。 */
    void restore() {
        if (saved < 0) {
            return;
        }
        if (audioManager == null || stream < 0) {
            reset();
            return;
        }
        int now = audioManager.getStreamVolume(stream);
        int maxNow = audioManager.getStreamMaxVolume(stream);
        String devicesNow = devicesOf(stream);

        if (now != appliedTarget) {
            Log.i(TAG, "volume restore skipped: user changed it (" + appliedTarget + " -> " + now + ")");
            reset();
            return;
        }
        if (maxNow != appliedMax) {
            Log.i(TAG, "volume restore skipped: max changed (" + appliedMax + " -> " + maxNow + ")");
            reset();
            return;
        }
        if (appliedDevices != null && !appliedDevices.equals(devicesNow)) {
            Log.i(TAG, "volume restore skipped: output devices changed ["
                    + appliedDevices + "] -> [" + devicesNow + "]");
            reset();
            return;
        }

        try {
            audioManager.setStreamVolume(stream, saved, 0);
        } catch (RuntimeException exception) {
            Log.w(TAG, "volume restore failed", exception);
        }

        if (audioManager.getStreamVolume(stream) != saved) {
            try {
                audioManager.setStreamVolume(stream, saved, 0);
            } catch (RuntimeException ignored) {
            }
            if (audioManager.getStreamVolume(stream) != saved) {
                Log.w(TAG, "volume restore dropped after retry: want=" + saved
                        + " got=" + audioManager.getStreamVolume(stream) + " stream=" + stream);
            } else {
                Log.i(TAG, "volume restored on retry to " + saved);
            }
        } else {
            Log.i(TAG, "volume restored to " + saved);
        }
        reset();
    }

    private void reset() {
        saved = -1;
        stream = -1;
        appliedTarget = -1;
        appliedMax = -1;
        appliedDevices = null;
    }

    private static int resolveStream(AudioAttributes attributes) {
        if (attributes != null) {
            try {
                return attributes.getVolumeControlStream();
            } catch (RuntimeException ignored) {
            }
        }
        return AudioManager.STREAM_MUSIC;
    }

    /**
     * 当前流的输出设备集合快照，用于判断恢复时是否还落在同一批设备上。
     * getDevicesForStream 在新 SDK 里已从公开 API 移除，反射取；取不到就返回 null，
     * 调用方据此跳过设备校验，只保留读回校验。
     */
    private String devicesOf(int targetStream) {
        try {
            Method method = AudioManager.class.getMethod("getDevicesForStream", int.class);
            Object value = method.invoke(audioManager, targetStream);
            if (value instanceof AudioDeviceInfo[]) {
                AudioDeviceInfo[] devices = (AudioDeviceInfo[]) value;
                if (devices.length == 0) {
                    return "";
                }
                StringBuilder builder = new StringBuilder();
                for (AudioDeviceInfo device : devices) {
                    builder.append(device.getId()).append(':').append(device.getType()).append(',');
                }
                return builder.toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
