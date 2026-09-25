package com.mmggh.vollaflashfix;

import android.hardware.camera2.CameraCharacteristics;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * VollaFlashFix
 *
 * The Volla Quintus (algiz, mt6877) has a dual-LED flash driven by an MT6360.
 * Both LEDs are exposed as independent kernel sysfs channels:
 *   /sys/class/leds/mt6360_flash_ch1/brightness   (max_brightness 31)
 *   /sys/class/leds/mt6360_flash_ch2/brightness   (max_brightness 31)
 *
 * The stock MediaTek camera HAL reports the flash as a single channel with
 * torchStrengthMaxLevel = 1, so:
 *   - Only ONE LED ever lights (cust_isDualFlashSupport() returns 0 in
 *     libcameracustom.flashlight.so), and
 *   - Android hides the torch-strength slider because the max level is 1.
 *
 * This module bypasses the broken HAL entirely. It hooks the framework
 * CameraManager / CameraCharacteristics that both the system flashlight
 * (SystemUI) and the FlashDim app use, spoofs a multi-level strength range,
 * and redirects the actual on/off + strength writes straight to BOTH kernel
 * sysfs channels via root. That gives:
 *   - Both LEDs lighting together, and
 *   - A working brightness slider in the system flashlight UI and in FlashDim.
 *
 * Scope both com.android.systemui and com.cyb3rko.flashdim in LSPosed.
 */
public class FlashDimHook implements IXposedHookLoadPackage {

    // Kernel channels for the two flash LEDs and their hardware max.
    private static final int HW_MAX = 31;
    private static final String SYSFS_CH1 = "/sys/class/leds/mt6360_flash_ch1/brightness";
    private static final String SYSFS_CH2 = "/sys/class/leds/mt6360_flash_ch2/brightness";

    // Packages we instrument. SystemUI hosts the OS flashlight tile; FlashDim
    // is the popular manual brightness app.
    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_FLASHDIM = "com.cyb3rko.flashdim";

    // Cache the last non-zero level so a plain setTorchMode(on) without an
    // explicit strength still uses the user's last chosen brightness.
    private static volatile int sLastLevel = HW_MAX;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;
        if (!PKG_SYSTEMUI.equals(pkg) && !PKG_FLASHDIM.equals(pkg)) return;

        XposedBridge.log("VollaFlashFix: loaded into " + pkg);

        hookCharacteristicsStrength(lpparam);

        // Public CameraManager API. FlashDim calls torch through here.
        hookGetTorchStrengthLevel(lpparam, "android.hardware.camera2.CameraManager");
        hookTurnOnTorchWithStrengthLevel(lpparam, "android.hardware.camera2.CameraManager");
        hookSetTorchMode(lpparam, "android.hardware.camera2.CameraManager");

        // Internal singleton. SystemUI's FlashlightController drives the torch
        // through CameraManagerGlobal directly, bypassing the public wrapper,
        // so we must hook it here too or the system flashlight tile takes the
        // stock single-LED HAL path.
        hookGetTorchStrengthLevel(lpparam, "android.hardware.camera2.CameraManager$CameraManagerGlobal");
        hookTurnOnTorchWithStrengthLevel(lpparam, "android.hardware.camera2.CameraManager$CameraManagerGlobal");
        hookSetTorchMode(lpparam, "android.hardware.camera2.CameraManager$CameraManagerGlobal");
    }

    /**
     * Spoof FLASH_INFO_STRENGTH_MAXIMUM_LEVEL / DEFAULT so the OS exposes a
     * strength slider. Without this the flashlight UI shows a plain toggle.
     */
    private void hookCharacteristicsStrength(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CameraCharacteristics", lpparam.classLoader,
                "get", CameraCharacteristics.Key.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args[0] == null) return;
                        String key = param.args[0].toString();
                        if (key.contains("STRENGTH_MAXIMUM_LEVEL")
                                || key.contains("strengthMaximumLevel")) {
                            param.setResult(HW_MAX);
                        } else if (key.contains("STRENGTH_DEFAULT_LEVEL")
                                || key.contains("strengthDefaultLevel")) {
                            param.setResult(HW_MAX);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log("VollaFlashFix: characteristics hook failed: " + t);
        }
    }

    /**
     * Report the current level. We return our cached value so the UI slider
     * position stays consistent with what we actually wrote to the kernel.
     */
    private void hookGetTorchStrengthLevel(LoadPackageParam lpparam, String className) {
        try {
            XposedHelpers.findAndHookMethod(
                className, lpparam.classLoader,
                "getTorchStrengthLevel", String.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return sLastLevel;
                    }
                });
            XposedBridge.log("VollaFlashFix: hooked getTorchStrengthLevel on " + className);
        } catch (Throwable t) {
            XposedBridge.log("VollaFlashFix: getTorchStrengthLevel hook skipped on " + className + ": " + t);
        }
    }

    /**
     * The strength-aware torch entry point. Clamp the requested level and
     * drive BOTH LED channels directly.
     */
    private void hookTurnOnTorchWithStrengthLevel(LoadPackageParam lpparam, String className) {
        try {
            XposedHelpers.findAndHookMethod(
                className, lpparam.classLoader,
                "turnOnTorchWithStrengthLevel", String.class, int.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        int level = (int) param.args[1];
                        if (level > HW_MAX) level = HW_MAX;
                        if (level < 1) level = 1;
                        sLastLevel = level;
                        writeBoth(level);
                        return null;
                    }
                });
            XposedBridge.log("VollaFlashFix: hooked turnOnTorchWithStrengthLevel on " + className);
        } catch (Throwable t) {
            XposedBridge.log("VollaFlashFix: turnOnTorchWithStrengthLevel hook skipped on " + className + ": " + t);
        }
    }

    /**
     * Plain on/off toggle. On -> restore last brightness on both channels.
     * Off -> zero both channels. We fully replace the method so the broken
     * single-channel HAL path is never taken.
     */
    private void hookSetTorchMode(LoadPackageParam lpparam, String className) {
        try {
            XposedHelpers.findAndHookMethod(
                className, lpparam.classLoader,
                "setTorchMode", String.class, boolean.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        boolean enabled = (boolean) param.args[1];
                        writeBoth(enabled ? sLastLevel : 0);
                        return null;
                    }
                });
            XposedBridge.log("VollaFlashFix: hooked setTorchMode on " + className);
        } catch (Throwable t) {
            XposedBridge.log("VollaFlashFix: setTorchMode hook skipped on " + className + ": " + t);
        }
    }

    /** Write the same level to both LED channels via a single root shell. */
    private void writeBoth(int level) {
        try {
            String cmd = "echo " + level + " > " + SYSFS_CH1
                    + "; echo " + level + " > " + SYSFS_CH2;
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
            XposedBridge.log("VollaFlashFix: wrote level " + level + " to ch1+ch2");
        } catch (Exception e) {
            XposedBridge.log("VollaFlashFix: root write failed: " + e.getMessage());
        }
    }
}
