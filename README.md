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
- **Continuous speech monitoring** (slur detection) runs while the app is open:
  mic → VAD → on-device acoustic features + Deepgram → personal-baseline detector
  → alerts (notification, auto-FAST, optional SMS). See below.

### Not wired up yet

- `video/MediaPipeFaceAnalyzer` — needs `face_landmarker.task` in `app/src/main/assets`.
- `sensor/BluetoothSppSource`, `BleSensorSource`, `WifiSensorSource` — pick one for
  the Uno (HC-05 Classic BT, HM-10/ESP32 BLE, or an ESP WiFi gateway).
- Deepgram key should eventually be proxied through a backend instead of shipped in
  the APK.
- Monitoring is **foreground-only** (stops when the app is backgrounded). The
  `SpeechMonitor.begin/end` seam is where a foreground-service implementation can be
  added later.

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

## Continuous speech monitoring (slur detection)

Open **Home → Continuous monitoring**. The flow:

1. Accept the consent notice (continuous audio is sensitive).
2. Tap **Calibrate voice baseline (20s)** and speak naturally. A personal
   baseline (per-feature mean/std) is saved to `filesDir` as JSON. Only derived
   features are stored — **never raw audio**.
3. Toggle monitoring on. It runs only while the app is in the foreground; it
   resumes automatically when you return if it was left enabled.

How detection works:

- **VAD** (energy + zero-crossing) gates audio so silence is never sent to Deepgram.
- **On-device acoustic features**: pitch, jitter, shimmer, harmonics-to-noise
  ratio, spectral centroid, and 4 Hz envelope-modulation rhythm.
- **Deepgram** (Nova-3) adds transcript, word confidence, speech rate, pause ratio,
  and filler ratio. One persistent session with KeepAlive + auto-reconnect.
- **`SlurDetector`** compares every ~2 s window to your baseline using z-scores,
  smooths with EWMA, and uses CUSUM to require a sustained change before alerting.
- **Alerts**: notification + in-app banner, an auto-run FAST assessment, and an
  optional SMS to an emergency contact (configure in **Settings → Emergency alerts**).
  The SMS has a 15 s cancel window to protect against false positives.

Required permissions: `RECORD_AUDIO`, `POST_NOTIFICATIONS` (Android 13+), and
`SEND_SMS` (only if SMS alerts are enabled).

Tune sensitivity with the slider on the monitor screen (higher = more sensitive).

---

## Tests

JVM unit tests cover the DSP and detector (no device needed):

```bash
./gradlew :app:testDebugUnitTest
```

`DspTest` verifies pitch, jitter/shimmer, RMS, ZCR, spectral centroid and FFT on
synthetic signals; `SlurDetectorTest` verifies baseline comparison and alerting.

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
  domain/        Assessment, ModuleResult, Metric, RiskBand, MonitorModels
  video/         FaceAnalyzer, AsymmetryCalculator (MediaPipe mirror-pair math)
  audio/         AudioCapture, DeepgramClient, DeepgramStream, Vad, Dsp,
                 AcousticFeatureExtractor, SlurDetector, SpeechAnalyzer, SpeechSession,
                 SpeechMonitor
  alerts/        AlertManager (notifications, auto-FAST, SMS)
  sensor/        SensorSource, MockSensorSource, BluetoothSppSource, BleSensorSource,
                 WifiSensorSource, SensorRepository
  scoring/       StrokeRiskScorer (weighted FAST bands)
  data/          SettingsStore, BaselineStore
app/src/test/java/com/hackmit/app/audio/   DspTest, SlurDetectorTest
tools/rename.sh
```

### Tech stack

Kotlin 2.0.21 · Jetpack Compose (Material 3) · Navigation Compose · CameraX ·
MediaPipe Tasks Vision · OkHttp (Deepgram WebSocket) · DataStore · Lifecycle
Process · AGP 8.9.2 · Gradle 8.14.4 · compile/target SDK 35 · min SDK 26.

---

## Next steps

1. Drop `face_landmarker.task` into `app/src/main/assets` and implement
   `MediaPipeFaceAnalyzer` on top of `AsymmetryCalculator`.
2. Implement one Arduino transport (`BluetoothSppSource` is the quickest for an
   Uno + HC-05) and select it in Settings.
3. Tune slur thresholds with recorded normal vs. slurred clips (a WAV replay
   harness is the next testing addition).
4. Add a foreground service so monitoring survives backgrounding.
5. Move the Deepgram key behind a small backend proxy.
