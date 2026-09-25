# VollaFlashFix

Xposed/LSPosed module that fixes the flashlight on the **Volla Quintus (algiz, MediaTek mt6877)**.

## The problem

The Quintus has a **dual-LED flash** driven by an MT6360, exposed as two independent kernel channels:

```
/sys/class/leds/mt6360_flash_ch1/brightness   (max_brightness 31)
/sys/class/leds/mt6360_flash_ch2/brightness   (max_brightness 31)
```

Both channels work perfectly at the kernel level. The problem is entirely in MediaTek's proprietary camera HAL:

- `libcameracustom.flashlight.so` → `cust_isDualFlashSupport()` and `cust_isSubFlashSupport()` are hard-coded to return `0`, so only **one LED** ever lights.
- The camera provider reports `torchStrengthMaxLevel = 1`, so Android **hides the brightness slider** and the torch is on/off only.

## What this module does

Instead of patching the closed-source HAL, it hooks the Android framework `CameraManager` / `CameraCharacteristics` used by both the system flashlight and FlashDim, and:

1. Spoofs `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL = 31` so the OS exposes a **brightness slider**.
2. Replaces `turnOnTorchWithStrengthLevel()` and `setTorchMode()` to write the chosen level **directly to both `ch1` and `ch2`** via root — lighting **both LEDs** and honouring the slider.

Scoped to:

- `com.android.systemui` — the OS flashlight tile / brightness slider
- `com.cyb3rko.flashdim` — the FlashDim app

## Requirements

- Volla Quintus (algiz) with root (KernelSU) so the module can `su -c` write to sysfs.
- LSPosed (or compatible Xposed framework), API 82+.

## Install

1. Grab the APK from the [Actions artifacts](../../actions) or a [Release](../../releases).
2. Install it, enable the module in LSPosed, and tick both scoped apps.
3. Reboot (or restart SystemUI). Long-press the flashlight tile — the brightness slider now works and both LEDs light.

## Notes

- Brightness range is 1–31 (kernel `max_brightness`). Do **not** exceed it; the flash can overheat.
- If the framework method signatures differ on a future ROM update, check the LSPosed log for `VollaFlashFix:` lines.

## Credit

Based on the original single-app FlashDim hook, extended to cover SystemUI and dual-channel output.
