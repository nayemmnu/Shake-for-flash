# Twist Flashlight

An Android app that turns the flashlight on/off with a quick **twist/rotate**
motion of the phone — works even when the app is backgrounded or the screen
is off, via a foreground service.

## How the gesture works

The service listens to the **gyroscope** (angular velocity, rad/s). A fast
twist of the wrist spikes the combined rotation magnitude well above resting
levels; once it crosses a threshold the flashlight toggles, and the detector
"re-arms" only after the motion settles down again — so one twist = one
toggle, not a flicker. Devices without a gyroscope fall back to an
accelerometer-based shake detector automatically.

Tune the feel in `ShakeFlashlightService.kt`:
- `TWIST_TRIGGER_THRESHOLD` — lower it to make the gesture easier to trigger,
  raise it to require a harder twist.
- `TRIGGER_COOLDOWN_MS` — minimum time between two toggles.

## Project structure

```
app/src/main/java/com/example/shakeflashlight/
  MainActivity.kt              # UI: permissions + on/off switch
  ShakeFlashlightService.kt    # Foreground service: sensor + flashlight logic
app/src/main/AndroidManifest.xml
app/src/main/res/...
.github/workflows/build.yml    # Builds a debug APK automatically on GitHub
```

## Building the APK on GitHub (no local Android Studio needed)

1. Push this project to a new GitHub repository.
2. GitHub Actions will run automatically (see `.github/workflows/build.yml`)
   and build a debug APK on every push, or you can trigger it manually from
   the **Actions** tab → **Build APK** → **Run workflow**.
3. Once the run finishes, open it and download the **app-debug-apk**
   artifact (a zip containing `app-debug.apk`). Copy the APK to your phone
   and install it (you'll need to allow "install unknown apps" for whatever
   app you use to open the file).

## Building locally (optional, via Android Studio)

1. Open the project folder in Android Studio (Iguana or newer recommended).
2. If prompted, let Android Studio generate the Gradle wrapper — this repo
   ships without a committed `gradle-wrapper.jar` so it stays lightweight;
   Android Studio (or running `gradle wrapper` once if you have Gradle
   installed) creates it automatically on first sync.
3. Click **Run** to install directly on a connected device/emulator, or
   **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Permissions

- `CAMERA` — required by `CameraManager` to control the torch on some OEM
  builds. The app requests this at runtime.
- `POST_NOTIFICATIONS` (Android 13+) — required to show the persistent
  "service is active" notification.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CAMERA` — let the twist detector
  keep running while the app is backgrounded.

## Notes

- The flashlight is turned off automatically if the service is stopped.
- If the gesture stops working after the phone has been idle a long time,
  disable battery optimization for this app in your phone's Settings so
  Android doesn't kill the background service.
- Minimum supported Android version: 6.0 (API 23).
