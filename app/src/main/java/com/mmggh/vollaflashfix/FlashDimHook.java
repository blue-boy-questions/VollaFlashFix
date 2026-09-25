package com.mmggh.vollaflashfix;

import android.hardware.camera2.CameraCharacteristics;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.HashSet;

/**
 * VollaFlashFix
 *
 * The Volla Quintus (algiz, mt6877) has a dual-LED flash driven by an MT6360,
 * exposed as two independent kernel channels:
 *   /sys/class/leds/mt6360_flash_ch1/brightness   (max_brightness 31)
 *   /sys/class/leds/mt6360_flash_ch2/brightness   (max_brightness 31)
 *
 * Both channels work at the kernel level. The stock MediaTek camera HAL:
 *   - hard-codes cust_isDualFlashSupport()/cust_isSubFlashSupport() to 0, so
 *     only ONE LED ever lights, and
 *   - reports torchStrengthMaxLevel = 1, so Android hides the brightness slider.
 *
 * Rather than patch the closed HAL, we hook the framework camera2 torch entry
 * points and drive BOTH sysfs channels directly via root.
 *
 * IMPORTANT (learned from on-device logs): SystemUI's FlashlightController does
 * NOT go through the public android.hardware.camera2.CameraManager wrapper — it
 * calls the internal CameraManagerGlobal singleton directly. And the method
 * signatures on CameraManagerGlobal differ across ROMs (Android 16 / SDK 36 on
 * this device threw NoSuchMethodError for setTorchMode(String,boolean)).
 *
 * So instead of binding exact signatures we hook EVERY overload by name on both
 * classes (hookAllMethods) and parse arguments generically:
 *   - a boolean arg  -> on/off
 *   - an int arg     -> strength level (1..31)
 * This is resilient to signature changes between ROM versions.
 *
 * Scope both com.android.systemui and com.cyb3rko.flashdim in LSPosed.
 */
public class FlashDimHook implements IXposedHookLoadPackage {

    private static final int HW_MAX = 31;
    private static final String SYSFS_CH1 = "/sys/class/leds/mt6360_flash_ch1/brightness";
    private static final String SYSFS_CH2 = "/sys/class/leds/mt6360_flash_ch2/brightness";

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_FLASHDIM = "com.cyb3rko.flashdim";

    // Last non-zero level, so a plain "on" restores the user's chosen brightness.
    private static volatile int sLastLevel = HW_MAX;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;
        if (!PKG_SYSTEMUI.equals(pkg) && !PKG_FLASHDIM.equals(pkg)) return;

        XposedBridge.log("VollaFlashFix: loaded into " + pkg);

        hookCharacteristicsStrength(lpparam);

        // Public wrapper (used by FlashDim and some apps).
        hookTorchClass(lpparam, "android.hardware.camera2.CameraManager");
        // Internal singleton (used by SystemUI FlashlightController directly).
        hookTorchClass(lpparam, "android.hardware.camera2.CameraManager$CameraManagerGlobal");
    }

    /**
     * Spoof FLASH_INFO_STRENGTH_MAXIMUM_LEVEL / DEFAULT so the OS exposes a
     * brightness slider instead of a plain toggle.
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
            XposedBridge.log("VollaFlashFix: hooked CameraCharacteristics.get");
        } catch (Throwable t) {
            XposedBridge.log("VollaFlashFix: characteristics hook failed: " + t);
        }
    }

    /**
     * Hook every torch-related method (by name, all overloads) on the given
     * class. Signature-agnostic so it survives ROM differences.
     */
    private void hookTorchClass(LoadPackageParam lpparam, String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
        if (clazz == null) {
            XposedBridge.log("VollaFlashFix: class not found: " + className);
            return;
        }

        // One-time discovery: log the torch method names actually present.
        try {
            Set<String> names = new HashSet<>();
            for (Method m : clazz.getDeclaredMethods()) {
                String n = m.getName();
                if (n.toLowerCase().contains("torch")) names.add(m.toString());
            }
            for (String n : names) XposedBridge.log("VollaFlashFix: [" + className + "] found " + n);
        } catch (Throwable ignored) { }

        // Torch on/off + strength-aware variants. hookAllMethods catches every
        // overload with the given name; the generic handler figures out intent.
        String[] methodNames = {
            "setTorchMode",
            "turnOnTorchWithStrengthLevel",
            "setTorchModeChecked",   // some ROMs
        };
        for (String name : methodNames) {
            int count = XposedBridge.hookAllMethods(clazz, name, sTorchHandler).size();
            if (count > 0) {
                XposedBridge.log("VollaFlashFix: hooked " + count + "x " + name + " on " + className);
            }
        }

        // getTorchStrengthLevel -> return our cached value so slider stays synced.
        int gcount = XposedBridge.hookAllMethods(clazz, "getTorchStrengthLevel", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(sLastLevel);
            }
        }).size();
        if (gcount > 0) {
            XposedBridge.log("VollaFlashFix: hooked " + gcount + "x getTorchStrengthLevel on " + className);
        }
    }

    /**
     * Generic torch handler. Inspects args:
     *   - int present   -> strength level (clamped 1..31), turn on at that level
     *   - boolean false -> turn off
     *   - boolean true  -> turn on at last level
     * Fully replaces the original so the broken single-LED HAL path is skipped.
     */
    private static final XC_MethodHook sTorchHandler = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Integer level = null;
            Boolean enable = null;
            for (Object a : param.args) {
                if (a instanceof Integer && level == null) level = (Integer) a;
                else if (a instanceof Boolean && enable == null) enable = (Boolean) a;
            }

            int write;
            if (level != null) {
                int l = level;
                if (l > HW_MAX) l = HW_MAX;
                if (l < 0) l = 0;
                if (l > 0) sLastLevel = l;
                write = l;
            } else if (enable != null) {
                write = enable ? sLastLevel : 0;
            } else {
                // Unknown shape - do nothing, let original run.
                return;
            }

            writeBoth(write);
            // Suppress the original HAL call entirely.
            param.setResult(null);
        }
    };

    /** Write the same level to both LED channels via a single root shell. */
    private static void writeBoth(int level) {
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
