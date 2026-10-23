package org.prboomplus.rt;

import android.os.CombinedVibration;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.InputDevice;

import java.util.ArrayList;

/**
 * Controller rumble. SDL 2 has none on Android, so the game calls this
 * through JNI (see SDL/i_gamepad.c). Uses each controller's VibratorManager
 * (Android 12+), which exposes both motors of Xbox / PlayStation controllers.
 */
public final class GamepadRumble {

    private static final String TAG = "PrBoomRT";
    private static final long RESCAN_MS = 2000;

    private static final ArrayList<VibratorManager> managers = new ArrayList<>();
    private static final ArrayList<Vibrator> singles = new ArrayList<>();
    private static long lastScan = -RESCAN_MS;

    private GamepadRumble() {}

    private static void scan() {
        managers.clear();
        singles.clear();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null || dev.isVirtual()) {
                continue;
            }
            int src = dev.getSources();
            boolean pad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                       || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (!pad) {
                continue;
            }
            VibratorManager vm = dev.getVibratorManager();
            if (vm != null && vm.getVibratorIds().length > 0) {
                managers.add(vm);
            } else {
                Vibrator v = dev.getVibrator();
                if (v != null && v.hasVibrator()) {
                    singles.add(v);
                }
            }
        }
    }

    private static int amplitude(float f) {
        int a = Math.round(Math.max(0f, Math.min(1f, f)) * 255f);
        return a;
    }

    /**
     * low / high: heavy and light motor strength 0..1. Returns the number of
     * motors that were driven (0: no controller that can rumble).
     */
    public static int rumble(float low, float high, int ms) {
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastScan >= RESCAN_MS) {
                lastScan = now;
                scan();
            }
            if (ms <= 0) {
                ms = 1;
            }
            int lowAmp = amplitude(low), highAmp = amplitude(high);
            int motors = 0;

            for (VibratorManager vm : managers) {
                int[] ids = vm.getVibratorIds();
                if (lowAmp == 0 && highAmp == 0) {
                    vm.cancel();
                    continue;
                }
                CombinedVibration.ParallelCombination c = CombinedVibration.startParallel();
                for (int i = 0; i < ids.length; i++) {
                    // controllers list the heavy (left) motor first
                    int amp = ids.length == 1 ? Math.max(lowAmp, highAmp) : (i == 0 ? lowAmp : highAmp);
                    if (amp > 0) {
                        c.addVibrator(ids[i], VibrationEffect.createOneShot(ms, amp));
                        motors++;
                    }
                }
                vm.vibrate(c.combine());
            }

            for (Vibrator v : singles) {
                int amp = Math.max(lowAmp, highAmp);
                if (amp == 0) {
                    v.cancel();
                } else {
                    v.vibrate(VibrationEffect.createOneShot(ms, amp));
                    motors++;
                }
            }
            return motors;
        } catch (Exception e) {
            Log.w(TAG, "rumble failed", e);
            return 0;
        }
    }
}
