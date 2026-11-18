# Torch Controller (Android)

A floating joystick-gesture helper prototype for personal testing with Torchlight: Infinite.

## Build
Open the **Actions** tab and run **Build Torch Controller APK**, or push changes to the `iris-controller` branch. Download the `torch-controller-debug` artifact from the completed workflow.

## First use
1. Install the APK.
2. Allow **Display over other apps**.
3. Open Android Accessibility settings and enable **Torch Controller**.
4. Return to the app and start the floating controller.
5. Open Torchlight. Drag the green crosshair so its centre sits on the joystick's resting centre.
6. Tap **AUTO** for a short test. It cycles up, right, down, left. Tap **STOP** immediately to stop.

The app sends gestures through Android AccessibilityService. Android/game versions may reject simulated gestures. This is not a screen-reading AI: it cannot detect enemies, collect loot, or read health. It does not bypass anti-cheat or guarantee compatibility with game rules. Automation may violate the game's terms and risk account action.

## Safety
Test in a safe area, keep STOP visible, and supervise it. This is an early prototype, not an autonomous farming bot.
