package com.sakura.airannounce;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 播报音量提升的落盘保险。
 *
 * 为什么必须落盘：boost 之后「原音量」只存在内存里，而这类 ROM 会把本进程冻进
 * cgroup freezer —— 进程一冻，app 内的 volumeSafetyRestore 定时器立刻停摆，
 * 音量就停在抬高值上，插着耳机会直接冲耳朵，而且只能等用户自己发现。
 * 所以原值要写到磁盘，让任何后来者都能还原：
 *
 *   - 本进程重启 / 监听服务重建（{@link NotificationReaderService} 的 onListenerConnected）
 *   - 前台保活服务每 60 秒的自检（{@link KeepAliveService}）
 *   - root 守护模块的 5 秒节拍（keepalive/service.sh）
 *
 * 文件格式（单行，空格分隔）：
 *
 *   original  target  max  leaseUntilWallMs  deviceKind
 *
 * 三个关键设计，都是为了不被自己误伤：
 *
 *   1. **租约**。文件在整个会话期间都存在，守护每 5 秒就看一次；如果只看「文件在」
 *      就恢复，念到一半音量就会被压回去。所以每次 speak 都续租 leaseUntil，
 *      别人只在租约过期后才动手。
 *   2. **设备**。音量是按输出设备分别存的。耳机已经拔了却把原值写进去，写的是
 *      扬声器那一档，耳机那档还留在 boost 值上——下次连上直接炸耳朵。
 *      所以记设备类别，恢复时设备对不上就先不动。
 *   3. **比对**。用户在这期间自己拧过音量的话，原值已经过期了，不能覆盖他的选择
 *      （A2DP 绝对音量有取整，留 ±1 档容差）。
 */
final class SpeechVolumeGuard {
    private static final String TAG = "AirAnnounce";

    static final String FILE_NAME = "volume_original";
    /** 应用侧判定「还来得及正常收尾」的宽限：boost 与落盘是同一动作，别自己打自己。 */
    private static final long APP_GRACE_MS = 5_000L;
    /** 音量档位比对容差：A2DP 绝对音量换算会差一档。 */
    private static final int VOLUME_TOLERANCE = 1;

    static final String DEVICE_BLUETOOTH = "bt";
    static final String DEVICE_OTHER = "other";
    /** 租约最长可信时长：超过这个数就是时钟回拨，按已过期处理。 */
    private static final long MAX_LEASE_MS = 10 * 60_000L;

    private SpeechVolumeGuard() {
    }

    /**
     * 当前蓝牙输出设备的 id，没有则 -1。
     *
     * 为什么要 id 而不是只记「是不是蓝牙」：音量是按设备分别存的。A 耳机 boost 后
     * 断开、B 耳机连上，只记 kind 的话两者都是 bt，会拿 A 的原值去对 B —— 轻则
     * 比对付不过而弃文件，重则把 B 的音量改成 A 的值，而 A 那一档永远留在提升值上。
     */
    static int currentBluetoothDeviceId(Context context) {
        AudioManager audioManager =
                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return -1;
        }
        try {
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                switch (device.getType()) {
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                    case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                    case AudioDeviceInfo.TYPE_BLE_HEADSET:
                    case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                    case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                        return device.getId();
                    default:
                        break;
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "device id check failed", exception);
        }
        return -1;
    }

    /** 当前是否有蓝牙音频输出。恢复音量前必须确认设备还在。 */
    static String currentDeviceKind(Context context) {
        return currentBluetoothDeviceId(context) > 0 ? DEVICE_BLUETOOTH : DEVICE_OTHER;
    }

    /**
     * 落盘记录是否仍然有效（租约未到期）。
     * 给「内存以为提过音量、但磁盘记录已被外部还原删除」这种情况做复核。
     */
    static boolean isPresent(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) {
            return false;
        }
        String raw = readAll(file);
        if (raw == null) {
            return false;
        }
        String[] parts = raw.trim().split(" ");
        if (parts.length < 5) {
            return false;
        }
        try {
            long leaseUntil = Long.parseLong(parts[3]);
            long now = System.currentTimeMillis();
            if (leaseUntil - now > MAX_LEASE_MS) {
                return false;
            }
            return now < leaseUntil;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** boost 成功后立刻落盘。写失败只是丢了一次保险，不影响正常播报。 */
    static void record(Context context, int original, int target, int max, long leaseUntilMs) {
        write(context, join(context, original, target, max, leaseUntilMs));
    }

    /** 会话进行中续租：让「租约过期」只代表应用真的没在播报了。 */
    static void refresh(Context context, int original, int target, int max, long leaseUntilMs) {
        write(context, join(context, original, target, max, leaseUntilMs));
    }

    static void clear(Context context) {
        try {
            new File(context.getFilesDir(), FILE_NAME).delete();
        } catch (Throwable ignored) {
            // 删不掉只意味着下次启动会多做一次无害的还原
        }
    }

    /**
     * 发现「上次提了音量却没还原」就补一次。
     *
     * @param speakingNow 本进程当前是否正在播报：正在播报时不能动音量，
     *                    否则会把用户正在听的内容压回原值。
     * @return true 表示确实执行了还原
     */
    static boolean recoverIfNeeded(Context context, boolean speakingNow) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) {
            return false;
        }
        if (speakingNow) {
            // 会话还活着：这个文件是有效的租约，不是残留
            return false;
        }
        long age = System.currentTimeMillis() - file.lastModified();
        if (age >= 0 && age < APP_GRACE_MS) {
            return false;
        }
        String raw = readAll(file);
        if (raw == null) {
            file.delete();
            return false;
        }
        String[] parts = raw.trim().split("\\s+");
        if (parts.length < 5) {
            // 旧格式或写坏了：删掉，别每 60 秒读一次垃圾
            file.delete();
            return false;
        }
        int original;
        int target;
        long leaseUntil;
        String deviceKind;
        int deviceId = -1;
        try {
            original = Integer.parseInt(parts[0]);
            target = Integer.parseInt(parts[1]);
            leaseUntil = Long.parseLong(parts[3]);
            deviceKind = parts[4];
            if (parts.length >= 6) {
                deviceId = Integer.parseInt(parts[5]);
            }
        } catch (RuntimeException exception) {
            file.delete();
            return false;
        }
        long now = System.currentTimeMillis();
        if (leaseUntil - now > MAX_LEASE_MS) {
            // 时钟回拨：租约看起来永远有效，实际已经不可信，按已过期处理
            Log.w(TAG, "volume marker lease is in the future, treating as expired.");
            leaseUntil = 0L;
        }
        if (now < leaseUntil) {
            // 租约还没到期：可能是别的进程正在播报（本进程只是没拿到实例）
            return false;
        }
        if (DEVICE_BLUETOOTH.equals(deviceKind)) {
            int currentId = currentBluetoothDeviceId(context);
            if (currentId <= 0) {
                // 原值是给耳机的。现在耳机不在，写进去等于改扬声器那一档，
                // 而耳机那一档还停在 boost 值上——等设备回来再处理。
                Log.i(TAG, "volume marker kept: original device is gone.");
                return false;
            }
            if (deviceId > 0 && currentId != deviceId) {
                // 换成了另一副耳机：原值属于上一副，不能拿来改这一副的音量。
                // 保留文件，等原来那副回来再处理。
                Log.i(TAG, "volume marker kept: another headset is connected.");
                return false;
            }
        }
        AudioManager audioManager =
                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return false;
        }
        try {
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            file.delete();
            if (Math.abs(current - target) > VOLUME_TOLERANCE) {
                // 用户自己拧过音量：他的选择优先，原值已经没意义了
                Log.i(TAG, "volume marker dropped: user changed volume "
                        + target + " -> " + current);
                return false;
            }
            if (current == original) {
                return false;
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0);
            Log.i(TAG, "volume restored from disk marker: " + current + " -> " + original);
            NotificationReaderService.recordEvent(
                    "音量兜底：上次播报没还原，已恢复为 " + original);
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "volume restore from marker failed", exception);
            return false;
        }
    }

    private static String join(Context context, int original, int target, int max,
            long leaseUntilMs) {
        int deviceId = currentBluetoothDeviceId(context);
        String kind = deviceId > 0 ? DEVICE_BLUETOOTH : DEVICE_OTHER;
        return original + " " + target + " " + max + " " + leaseUntilMs + " " + kind
                + " " + deviceId;
    }

    private static void write(Context context, String content) {
        File dir = context.getFilesDir();
        File target = new File(dir, FILE_NAME);
        File temp = new File(dir, FILE_NAME + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(temp);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            // 进程随时可能被冻住，必须真的落盘再 rename，否则内容可能还在页缓存里
            out.getFD().sync();
            out.close();
            out = null;
            if (!temp.renameTo(target)) {
                Log.w(TAG, "volume marker rename failed");
                temp.delete();
            }
        } catch (IOException exception) {
            Log.w(TAG, "volume marker write failed", exception);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 已在异常路径，关闭失败没有补救手段
                }
            }
        }
    }

    private static String readAll(File file) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            byte[] buffer = new byte[(int) Math.min(file.length() + 1, 128)];
            int read = in.read(buffer);
            if (read <= 0) {
                return null;
            }
            return new String(buffer, 0, read, "UTF-8");
        } catch (IOException exception) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 读失败已经没有补救手段
                }
            }
        }
    }
}
