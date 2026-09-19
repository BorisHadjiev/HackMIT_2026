# StrokeSense

Android app for early **stroke screening** using the FAST signs. It combines three
signals into one risk readout:

| Module | Signal | Source |
| --- | --- | --- |
| Facial symmetry | drooping face / asymmetry | phone camera (MediaPipe FaceMesh) |
| Speech | slurred speech | phone mic streamed to Deepgram |
| Motor / balance | arm drift + tremor | Arduino Uno IMU (gyro/accelerometer) |

> **Not a medical device.** Screening aid only. If you suspect a stroke, call
> emergency services immediately.

---

## Current status

The full flow is built and runs on a physical phone:

`Home → Face calibration → Face test → Speech calibration → Speech test → Motor calibration → Motor test → Results`

- All screens work today on **mock data** (badged `DEMO DATA`) so the demo never
  depends on hardware.
- Each test screen has a **Simulate abnormal** switch to show a positive screen.
- The face screens show a **live front camera preview** (CameraX).
- Speech calibration/test stream **real microphone audio to Deepgram** when an API
  key is configured; otherwise they fall back to simulated transcripts.
- The motor module uses a `MockSensorSource`; the Arduino transports are stubbed
  with clear TODOs.

### Not wired up yet

- `video/MediaPipeFaceAnalyzer` — needs `face_landmarker.task` in `app/src/main/assets`.
- `sensor/BluetoothSppSource`, `BleSensorSource`, `WifiSensorSource` — pick one for
  the Uno (HC-05 Classic BT, HM-10/ESP32 BLE, or an ESP WiFi gateway).
- Deepgram key should eventually be proxied through a backend instead of shipped in
  the APK.

---

## Requirements

- JDK 17+ (JDK 21 tested)
- Android SDK with:
  - `platforms;android-35`
  - `build-tools;35.0.0`
  - `platform-tools`
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` pointing at the SDK
- An Android phone with **USB debugging** enabled (min Android 8.0 / API 26)

The Gradle wrapper (`./gradlew`) downloads Gradle 8.14.4 automatically.

### Installing the SDK (CLI, no Android Studio needed)

```bash
export ANDROID_HOME="$HOME/ext/Coding/android/sdk"
# one-time: download commandline-tools into $ANDROID_HOME/cmdline-tools/latest
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

---

## Build and run

```bash
# Build the debug APK
./gradlew :app:assembleDebug

# Build + install on the connected phone
./gradlew installDebug

# Or install an already-built APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open **StrokeSense** on the phone. On first use, grant the camera and
microphone permissions (the app asks on the relevant screens).

Quick sanity check:

```bash
adb devices                       # phone should be listed
adb shell am start -n com.hackmit.strokesense/com.hackmit.app.MainActivity
```

---

## Deepgram setup (speech module)

The key can be provided two ways, in priority order:

1. **In the app:** Settings (gear icon) → paste the key → *Save key*.
2. **At build time (convenient for demos):** add it to `local.properties`
   (gitignored, never committed):

   ```properties
   DEEPGRAM_API_KEY=your_key_here
   ```

   It is exposed to the app as `BuildConfig.DEEPGRAM_API_KEY` and used as the
   default when nothing is saved in Settings.

Without a key, the speech screens run in demo mode with simulated transcripts.

---

## Rename the app

The display name and application id live in `gradle.properties`:

```properties
APP_NAME=StrokeSense
APP_ID=com.hackmit.strokesense
```

One-command rename (no source files move — the internal Kotlin namespace stays
`com.hackmit.app` on purpose):

```bash
./tools/rename.sh "New Name" com.example.newid
```

---

## Project layout

```
app/src/main/java/com/hackmit/app/
  MainActivity.kt, StrokeApplication.kt
  ui/            theme, nav graph (StrokeApp.kt), AssessmentViewModel, screens/, components/
  domain/        Assessment, ModuleResult, Metric, RiskBand
  video/         FaceAnalyzer, AsymmetryCalculator (MediaPipe mirror-pair math)
  audio/         AudioCapture, DeepgramClient, SpeechAnalyzer, SpeechSession
  sensor/        SensorSource, MockSensorSource, BluetoothSppSource, BleSensorSource,
                 WifiSensorSource, SensorRepository
  scoring/       StrokeRiskScorer (weighted FAST bands)
  data/          SettingsStore (DataStore)
tools/rename.sh
```

### Tech stack

Kotlin 2.0.21 · Jetpack Compose (Material 3) · Navigation Compose · CameraX ·
MediaPipe Tasks Vision · OkHttp (Deepgram WebSocket) · DataStore · AGP 8.9.2 ·
Gradle 8.14.4 · compile/target SDK 35 · min SDK 26.

---

## Next steps

1. Drop `face_landmarker.task` into `app/src/main/assets` and implement
   `MediaPipeFaceAnalyzer` on top of `AsymmetryCalculator`.
2. Implement one Arduino transport (`BluetoothSppSource` is the quickest for an
   Uno + HC-05) and select it in Settings.
3. Calibrate scoring thresholds against real recordings.
4. Move the Deepgram key behind a small backend proxy.
