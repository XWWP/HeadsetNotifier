package com.sakura.airannounce;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 音量兜底恢复入口。
 *
 * 为什么不用主线程 Handler 兜底：播报期间应用进程被 cgroup 冻结时，主线程队列停摆，
 * postDelayed 到点也不会执行；AlarmManager 的非精确 set() 能把广播投递进被冻结的进程，
 * 这是唯一能在冻结状态下把音量拉回来的通道。
 */
public class VolumeRestoreReceiver extends BroadcastReceiver {

    private static final String TAG = "AirAnnounce";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !VolumeBoost.ACTION_RESTORE_VOLUME.equals(intent.getAction())) {
            return;
        }
        Log.i(TAG, "VolumeRestoreReceiver: safety restore fired");
        NotificationReaderService service = NotificationReaderService.getInstance();
        if (service != null) {
            // 服务还在：走内存里的基准，能带上设备集校验
            service.restoreVolumeFromSafetyAlarm();
            return;
        }
        VolumeBoost.restoreFromPersisted(context);
    }
}
