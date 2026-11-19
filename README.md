# Tap Controller (Android)

Four movable floating tap targets with independent cooldown timers.

## Build
Run **Build Torch Controller APK** from GitHub Actions or push to the `iris-controller` branch. Download the `torch-controller-debug` artifact from a completed run.

## Setup
1. Install APK.
2. Allow Display over other apps.
3. Enable Accessibility gesture access.
4. Start Live Teach AI and approve screen capture.
5. Show the floating controller and drag T1–T4 over the desired in-game skill buttons.
6. Tap each bubble to toggle the skills AI is allowed to use.
7. Press GO AI to let it run continuously; press STOP AI or STOP ALL to stop immediately.

The AI only attempts enabled skill taps while the screen classifier detects possible combat. Its visual evidence is approximate; a change in candidate health-bar count is not proof that a skill caused damage. It does not currently navigate maps or guarantee autonomous map clearing. Learning history is stored locally on the phone.
