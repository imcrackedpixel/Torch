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
6. Choose a movement pattern and pace before starting AUTO.
7. Tap **AUTO · 5 MIN MAX** for a supervised test. Tap **STOP** immediately to stop.

## Movement controls
- **SQUARE**: up, right, down, left.
- **HORIZONTAL**: left, right.
- **VERTICAL**: up, down.
- **DIAGONAL**: four diagonal directions.
- **PACE** cycles through Gentle, Balanced and Quick, changing the hold duration and pause between gestures. Stop AUTO before changing settings.
- AUTO stops automatically after five minutes. STOP remains available at all times.

The app sends gestures through Android AccessibilityService. Android/game versions may reject simulated gestures. This is not a screen-reading AI: it cannot detect enemies, collect loot, or read health. It does not bypass anti-cheat or guarantee compatibility with game rules. Automation may violate the game's terms and risk account action.

## Safety
Test in a safe area, keep STOP visible, supervise every run, and stop if movement is unexpected. This is an early prototype, not an autonomous farming bot.
