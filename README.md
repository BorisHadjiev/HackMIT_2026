# StrokeSense

Android app for early **stroke screening** using the FAST signs. It combines three
signals into one risk readout:

| Module | Signal | Source |
| --- | --- | --- |
| Facial symmetry | drooping face / asymmetry | phone camera, analyzed on gx10 (MediaPipe + LR) |
| Speech | slurred speech | phone mic → Deepgram proxy + on-device DSP + WavLM AI |
| Motor / balance | arm drift + tremor | Arduino Uno IMU (gyro/accelerometer) |

> **Not a medical device.** Screening aid only. If you suspect a stroke, call
> emergency services immediately.

---

## Current status

The full flow is built and runs on a physical phone:

`Home → Face calibration → Face test → Speech calibration → Speech test → Motor calibration → Motor test → Results`

- **Face** uses the real **MediaPipe FaceLandmarker on the gx10 gateway** with a
  validated logistic-regression asymmetry score (CV AUC 0.84) and a live
  baseline-vs-actual comparison.
- **Speech** streams real microphone audio through the **backend Deepgram proxy**
  (the key stays server-side) and scores slur two ways: the on-device
  personal-baseline detector and a server **AI slur score** (WavLM, CV AUC
  0.95–0.997).
- **Slur demo** (Home → Slur demo) runs bundled recordings (healthy vs. real
  dysarthric patients) through the on-device detector with synchronized audio.
- **Continuous monitoring** (slur detection) runs while the app is open:
  mic → on-device acoustic features + Deepgram → personal-baseline detector +
  server AI score → alerts (notification, auto-FAST, optional SMS).
- The app reads instructions aloud via **local Kokoro TTS**, answers task
  questions via a **local voice agent** (Ollama), and **gates other speakers**
  out of Deepgram (server-side speaker verification).
- The motor module uses a `MockSensorSource`; the Arduino transports are stubbed
  with clear TODOs.

### Not wired up yet

- `sensor/BluetoothSppSource`, `BleSensorSource`, `WifiSensorSource` — pick one
  for the Uno (HC-05 Classic BT, HM-10/ESP32 BLE, or an ESP WiFi gateway).
- Monitoring is **foreground-only** (stops when the app is backgrounded). The
  `SpeechMonitor.begin/end` seam is where a foreground-service implementation can
  be added later.
- Linq care-alert delivery is configured but the integration token currently has
  **no provisioned phone number**, so sends return 502 until one is added.

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

## Speech transcription & Deepgram

The app streams audio to the **backend Deepgram proxy** by default
(`wss://work.tail043976.ts.net/v1/deepgram/stream`), so the Deepgram API key lives
on the gx10 server (`.env` → `DEEPGRAM_API_KEY`) and **never ships in the APK**.

- Per-device proxy override: `local.properties` → `DEEPGRAM_PROXY_URL`
  (defaults to the live gateway).
- Direct-to-Deepgram fallback is only used when no proxy is configured: set the
  key in the app (Settings → Speech transcription) or in the gitignored
  `local.properties` → `DEEPGRAM_API_KEY`.
- Without a key or proxy, the speech screens fall back to simulated transcripts.

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

- **Streaming:** all captured audio is forwarded to Deepgram (via the gateway
  proxy); Deepgram's server-side VAD (`SpeechStarted`/`UtteranceEnd`) drives the
  speech state.
- **On-device acoustic features:** pitch, jitter, shimmer, harmonics-to-noise
  ratio, spectral centroid, and 4 Hz envelope-modulation rhythm — computed over
  voiced 40 ms sub-frames.
- **Deepgram** (Nova-3) adds transcript, word confidence, speech rate, pause ratio,
  and filler ratio. One persistent session with KeepAlive + auto-reconnect.
- **`SlurDetector`** compares every ~2 s window to your personal baseline using
  z-scores, smooths with EWMA, and uses CUSUM to require a sustained change before
  alerting.
- **Server AI slur score:** every ~5 s while speech is audible the app uploads a
  4 s window to the gateway's WavLM classifier (`/v1/slur/analyze`, CV AUC 0.95)
  and shows it alongside the on-device detector.
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

## Care alerts with Linq

The results screen can send a concise screening summary to a trusted contact and,
for a high-concern result, offers a **Call emergency services** button. The call
button only opens Android's dialer after a confirmation tap; the app never places
an emergency call automatically.

Configure these in Settings → **Care alerts (Linq)**:

- an HTTPS URL for your app-owned alert gateway;
- an optional alert-gateway token for the local backend demo (not a Linq key);
- the trusted contact's E.164 phone number (for example, `+15551234567`);
- the local emergency number (defaults to `911`).

The phone posts an idempotent JSON envelope to the gateway, for example:

```json
{
  "alert_id": "uuid",
  "source": "assessment",
  "kind": "emergency_recommended",
  "severity": "urgent",
  "title": "URGENT: StrokeSense recommends emergency help",
  "body": "Screening score: 72%...",
  "recipient": { "name": "Caregiver", "phone": "+15551234567" }
}
```

That gateway—not the APK—must authenticate the user, restrict recipients to an
approved contact list, and call Linq using its server-side
`Authorization: Bearer` API token. This keeps the Linq secret out of the client. The
Android modules are deliberately reusable:

- `alert/AlertMessageFactory.kt`: builds no-test, assessment, Deepgram, Elastic,
  or other external-signal summaries without sending raw transcripts/audio.
- `alert/LinqAlertGateway.kt`: sends the envelope and preserves an optional Linq
  trace ID returned by the gateway.
- `alert/AlertRepository.kt`: the UI-facing coordinator; use
`AssessmentViewModel.sendExternalAlert(AlertSource.DEEPGRAM, ...)` or
  `AssessmentViewModel.sendExternalAlert(AlertSource.ELASTIC, ...)` for future
integrations.

For the companion FastAPI backend, put the Linq key in its uncommitted `.env` as
`LINQ_API_KEY`; do not add it to Android's `local.properties`, Settings, or any
tracked source file. The Android **Alert gateway token** field is only for that
backend's optional demo protection and is not a Linq credential.

For example, a future Deepgram policy can share metrics after the user finishes a
test (rather than streaming each transcript):

```kotlin
vm.sendExternalAlert(
    source = AlertSource.DEEPGRAM,
    title = "Speech screening needs attention",
    summary = "Low transcription confidence and long pauses were detected.",
    urgent = false,
)
```

Linq's messaging API uses an `X-LINQ-INTEGRATION-TOKEN`, and it supports delivery
webhooks and trace IDs, which the gateway should use for auditing/retry rather
than the phone attempting direct delivery. See the [Linq API overview](https://docs.linqapp.com/channel/imessage/v2/api/) and [webhook event guide](https://docs.linqapp.com/channel/imessage/guides/webhooks/events/).

---

## Backend: care-alert gateway (FastAPI + Linq)

The APK never holds the Linq key. A small FastAPI gateway (`backend/`) owns the
Linq integration token, enforces a recipient allowlist, dedupes retries by
`alert_id`, and sends the caregiver message via iMessage/RCS/SMS. Full docs:
`backend/README.md`.

### Live gateway (HackMIT demo)

- Public URL: `https://work.tail043976.ts.net` — served through **Tailscale
  Funnel**, so it is reachable by anyone on the public internet (no Tailscale
  account needed).
- Alert endpoint: `POST /v1/stroke-alerts`
- Auth: `X-Alert-Gateway-Token` header. The shared token is **not committed** —
  ask the team for the current one (it's a demo secret; rotate it if it leaks).
- Recipient allowlist is `*`, so each user sets their own trusted contact in-app
  without any server change.
- **Deepgram proxy:** the app streams audio to
  `wss://work.tail043976.ts.net/v1/deepgram/stream` (token-auth) by default, so
  the Deepgram key stays on the server. Override per-device via
  `local.properties` → `DEEPGRAM_PROXY_URL`.
- **Voice (local):** the gateway runs **Kokoro TTS** (`/v1/tts`) and a **local
  voice agent** (`/v1/agent`, Ollama). The app reads assessment instructions
  aloud, speaks personalized results, and preps an emergency script for the
  911 dialer.
- **Speaker gating:** enroll your voice (Continuous monitoring → Enroll my
  voice); the gateway then only forwards *your* speech to Deepgram — other
  voices never leave the LAN.
- **Server-side face:** `POST /v1/face/analyze` (MediaPipe FaceLandmarker +
  trained LR, threshold 0.5535) — the app's face screens use it.
- **Server-side slur:** `POST /v1/slur/analyze` (WavLM embedding + LR,
  threshold 0.945) — the live monitor and "Record 4s + AI score" use it.
- **Slur validation:** `tools/slur_eval/` reproduces the detector in Python and
  scores public dysarthria corpora (TORGO + UA-Speech with severity). The
  on-device detector reaches AUC 0.62–0.72; a learned WavLM classifier reaches
  **AUC 0.997 (TORGO) / 0.945 (pathological)**. See its `README.md`.

In the app, **Settings → Care alerts (Linq)**:

- Gateway URL: `https://work.tail043976.ts.net/v1/stroke-alerts`
- Gateway token: the shared demo token
- Trusted contact: your phone (E.164, e.g. `+15551234567`)

### Run the backend yourself

```bash
cd backend
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env        # set LINQ_API_TOKEN, LINQ_FROM_NUMBER, GATEWAY_TOKEN, ALLOWED_RECIPIENTS
.venv/bin/uvicorn gateway.main:app --host 127.0.0.1 --port 8000
```

On the demo server it runs as a **user systemd service**
(`strokesense-gateway`) bound to `127.0.0.1:8000` and fronted by
`tailscale funnel --bg 8000`. See `backend/deploy/install.sh`.

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
  video/         FaceAnalyzer, AsymmetryCalculator, FaceServer (client),
                 FaceAnalysisController
  audio/         AudioCapture, DeepgramClient, DeepgramStream, Vad, Dsp,
                 AcousticFeatureExtractor, SlurDetector, SlurServer (client),
                 SpeechAnalyzer, SpeechSession, SpeechMonitor, Tts, Speaker, Wav
  alerts/        AlertManager (notifications, auto-FAST, SMS)
  alert/         alert drafts, summary factory, secure Linq gateway client, repository
  sensor/        SensorSource, MockSensorSource, BluetoothSppSource, BleSensorSource,
                 WifiSensorSource, SensorRepository
  scoring/       StrokeRiskScorer (weighted FAST bands)
  data/          SettingsStore, BaselineStore
app/src/test/java/com/hackmit/app/audio/   DspTest, SlurDetectorTest
tools/rename.sh
```

### Tech stack

Kotlin 2.0.21 · Jetpack Compose (Material 3) · Navigation Compose · CameraX ·
OkHttp (Deepgram WebSocket, gateway clients) · DataStore · Lifecycle Process ·
AGP 8.9.2 · Gradle 8.14.4 · compile/target SDK 35 · min SDK 26.

**Backend (gx10):** FastAPI + uvicorn (2 workers) · SQLite · Tailscale Funnel ·
MediaPipe FaceLandmarker · WavLM (Transformers/PyTorch) · Kokoro TTS · Ollama ·
whisper.cpp · Deepgram / Linq APIs.

---

## Next steps

1. Implement one Arduino transport (`BluetoothSppSource` is the quickest for an
   Uno + HC-05) and select it in Settings.
2. Add a foreground service so monitoring survives backgrounding.
3. Provision a Linq phone number so care-alert delivery works end-to-end.
4. Calibrate the server AI slur score on phone-mic audio (domain adaptation), so
   normal phone speech isn't over-flagged.
