package com.sakura.airannounce;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.util.Log;

/**
 * 播报期间把音量键接到播报自己的音量流上。
 *
 * 背景：音量键默认目标是 STREAM_MUSIC，而播报挂在 STREAM_ASSISTANT。
 * 不接管的话，用户播报时按音量键改的是音乐，播报响度纹丝不动。
 *
 * 做法：播报开始时创建一个 STATE_PLAYING 的 MediaSession 并把播放音量设为 remote，
 * 系统会把音量键导向这个 session 的 {@link VolumeProvider}；播报结束立即 setActive(false)
 * 并释放，避免长期占着媒体会话影响用户自己的播放器。
 *
 * 用户通过音量键调过之后，{@link VolumeBoost} 的恢复判据会发现「当前值不再等于本模块写入值」，
 * 从而放弃写回——等于把最终音量交给用户。
 */
final class VolumeKeyBridge {

    private static final String TAG = "AirAnnounce";
    private static final String SESSION_TAG = "AirAnnounceVolume";

    private final Context context;
    private final AudioManager audioManager;
    private MediaSession session;

    VolumeKeyBridge(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** 播报开始时调用。返回 false 表示这次接管没有成功，音量键维持系统默认行为。 */
    boolean open(AudioAttributes attributes) {
        if (audioManager == null) {
            return false;
        }
        final int stream;
        try {
            stream = attributes != null
                    ? attributes.getVolumeControlStream()
                    : AudioManager.STREAM_MUSIC;
        } catch (RuntimeException exception) {
            return false;
        }
        final int max = audioManager.getStreamMaxVolume(stream);
        if (max <= 0) {
            return false;
        }
        try {
            if (session == null) {
                session = new MediaSession(context, SESSION_TAG);
                session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS);
            }
            session.setPlaybackToRemote(new VolumeProvider(
                    VolumeProvider.VOLUME_CONTROL_ABSOLUTE, max,
                    audioManager.getStreamVolume(stream)) {
                @Override
                public void onAdjustVolume(int direction) {
                    applyVolume(stream, max, audioManager.getStreamVolume(stream) + direction);
                }

                @Override
                public void onSetVolumeTo(int volume) {
                    applyVolume(stream, max, volume);
                }

                private void applyVolume(int targetStream, int limit, int value) {
                    int clamped = Math.max(0, Math.min(limit, value));
                    try {
                        audioManager.setStreamVolume(targetStream, clamped, 0);
                    } catch (RuntimeException exception) {
                        Log.w(TAG, "volume key apply failed", exception);
                        return;
                    }
                    int actual = audioManager.getStreamVolume(targetStream);
                    setCurrentVolume(actual);
                    Log.i(TAG, "volume key -> stream " + targetStream + " = " + actual
                            + " (max " + limit + ")");
                }
            });
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build());
            session.setActive(true);
            Log.i(TAG, "volume key bridge opened (stream " + stream + ", max " + max + ")");
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "volume key bridge open failed", exception);
            return false;
        }
    }

    /** 播报结束调用：立刻放弃媒体会话，让音量键回到系统默认目标。 */
    void close() {
        if (session == null) {
            return;
        }
        try {
            session.setActive(false);
            Log.i(TAG, "volume key bridge closed");
        } catch (RuntimeException ignored) {
        }
    }

    void release() {
        close();
        if (session == null) {
            return;
        }
        try {
            session.release();
        } catch (RuntimeException ignored) {
        }
        session = null;
    }
}
