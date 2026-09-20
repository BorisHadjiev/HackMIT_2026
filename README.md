# StrokeSense

Android app for early **stroke screening** using the FAST signs. It combines three
signals into one risk readout:

| Module | Signal | Source |
| --- | --- | --- |
| Facial symmetry | drooping face / asymmetry | phone camera, analyzed on gx10 (MediaPipe + LR) |
| Speech | slurred speech | phone mic → Deepgram proxy + on-device DSP + WavLM AI |
| Motor / balance | uneven arm raise (left vs. right arm angle) | Arduino **UNO Q** + two MPU-6050 IMUs over **BLE** |

> **Not a medical device.** Screening aid only. If you suspect a stroke, call
> emergency services immediately.

---

## Current status

The full flow is built and runs on a physical phone:

`Home → Face calibration → Face test → Speech calibration → Speech test → Motor calibration → Motor test → Results`

- **Face** uses the real **MediaPipe FaceLandmarker on the gx10 gateway** with a
  validated logistic-regression asymmetry score (CV AUC 0.84) and a live
  baseline-vs-actual comparison. The face screens also show a **live front
  camera preview** (CameraX).
- **Speech** streams real microphone audio through the **backend Deepgram proxy**
  (the key stays server-side) and scores slur two ways: the on-device
  personal-baseline detector and a server **AI slur score** (WavLM, CV AUC
  0.95–0.997). Without a configured backend they fall back to simulated
  transcripts.
- **Slur demo** (Home → Slur demo) runs bundled recordings (healthy vs. real
  dysarthric patients) through the on-device detector with synchronized audio.
- **Continuous monitoring** (slur detection) runs while the app is open:
  mic → on-device acoustic features + Deepgram → personal-baseline detector +
  server AI score → alerts (notification, auto-FAST, optional SMS).
- The app reads instructions aloud via **local Kokoro TTS**, answers task
  questions via a **local voice agent** (Ollama), and **gates other speakers**
  out of Deepgram (server-side speaker verification).
- The **motor module talks to real hardware over BLE** — see
  [Connecting the Arduino IMU](#connecting-the-arduino-imu). It shows live **Left /
  Right / Diff** cards, a three-series chart, and a filled human silhouette at the
  **bottom** of Motor calibration and Motor test (`BodyFigure.kt` /
  `BodyPoseFigure`). Arduino cue controls send cooldown (`SETCOOLDOWN`), sleep
  hours (`SETSLEEP`), and Start/Stop (`START` / `STOP`). **Disconnect** sends
  `STOP` then tears down GATT; Status becomes `Disconnected`.
- Every screen can still run on **mock data** (badged `DEMO DATA`) so the demo
  never depends on hardware, including a simulated uneven arm raise.
- Each test screen has a **Simulate abnormal** switch to show a positive screen.

### Not wired up yet

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

## Build and install

Gradle needs to find a JDK 17+ and the SDK. Set both before building:

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_SDK_ROOT="$HOME/ext/Coding/android/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
```

```powershell
# PowerShell equivalent
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
```

Then:

```bash
# Build the debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug

# Install the built APK on the connected phone
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or do both in one step
./gradlew installDebug
```

On Windows use `.\gradlew.bat` instead of `./gradlew`.

Then open **StrokeSense** on the phone. On first use, grant the camera, microphone and
Bluetooth permissions (the app asks on the relevant screens).

Quick sanity check:

```bash
adb devices                       # phone should be listed
adb shell am start -n com.hackmit.strokesense/com.hackmit.app.MainActivity
```

The installed package is `com.hackmit.strokesense`; the internal Kotlin namespace is
`com.hackmit.app` (see [Rename the app](#rename-the-app)). Compose, `minSdk 26`,
`targetSdk 35`.

---

## Connecting the Arduino IMU

Firmware for the **Arduino UNO Q** lives in [`arduino/`](arduino/) (App Lab layout).
Bluetooth is the UNO Q's **onboard BLE** (Qualcomm / Linux BlueZ). There is **no
HC-05** and no Classic Bluetooth SPP.

> ### The board will NOT appear in Android's Bluetooth settings
>
> The UNO Q advertises as a **BLE-only GATT peripheral** named `StrokeSense`. Android's
> system Bluetooth screen lists pairable Classic devices, so `StrokeSense` will normally
> never show up there and generally **cannot be bonded**. Do not try to pair it.
> Discovery happens **inside this app**. This is the single biggest source of confusion
> with this setup.

### Board files (`arduino/`)

| Path | Runs where | Role |
| --- | --- | --- |
| `arduino/sketch/sketch.ino` | STM32 MCU | IMUs, buzzers, `START` / `STOP` / `SETCOOLDOWN` / `SLEEP` latch |
| `arduino/python/main.py` | Debian **host** | Nordic UART BLE bridge (`StrokeSense`) |
| `arduino/systemd/strokesense-ble.service` | host systemd **user** unit | starts the bridge at boot |

The UNO Q is a Debian computer *and* an STM32 on one board. The MCU has no radio.
`python/main.py` must run on the **host**. The App Lab container **cannot do BLE**:
its compose file mounts no `/run/dbus/system_bus_socket`, is not on the host network,
and omits the `bluetooth` group. BLE errors in the App Lab log are expected if that
copy of `main.py` is left running; the host unit is what the phone talks to.

### Flash the MCU (Arduino App Lab)

1. Open the project in **Arduino App Lab** on the UNO Q.
2. Paste or open `arduino/sketch/sketch.ino` over App Lab's `sketch.ino` (Ctrl+A, paste), then upload.
3. Hold both IMUs **still and down** during the ~2 s calibration at boot.
4. You can also drop `arduino/python/main.py` into the App Lab `python/` folder, but **do not rely on App Lab to serve BLE**.

USB serial stays at **115200** and accepts the same commands as BLE (useful without a phone).

### Host BLE bridge (required)

Stage the `arduino` Python package into the host user site once (and again if `~/.local` is wiped). On the board, as user `arduino`:

```sh
docker ps                     # find the App Lab container name
docker cp <container>:/usr/local/lib/python3.13/site-packages \
    ~/.local/lib/python3.13/site-packages
# the container's PyGObject must NOT shadow the host one
rm -rf ~/.local/lib/python3.13/site-packages/gi \
       ~/.local/lib/python3.13/site-packages/pygobject-*.dist-info
python3 -c "from arduino.app_utils import Bridge; import gi; print(gi.__file__)"
# gi must resolve under /usr/lib/python3/dist-packages
```

Install the systemd **user** unit so the bridge starts on a cold boot with no PC attached (`loginctl enable-linger arduino`). This is **not** the same as App Lab "run at startup" — use `strokesense-ble.service` on the host:

```sh
install -Dm644 arduino/systemd/strokesense-ble.service \
    /home/arduino/.config/systemd/user/strokesense-ble.service
export XDG_RUNTIME_DIR=/run/user/1000
systemctl --user daemon-reload
systemctl --user enable --now strokesense-ble.service
loginctl enable-linger arduino
```

The unit runs `/usr/bin/python3 /home/arduino/ArduinoApps/strokesense/python/main.py`
(`Restart=always`). Copy `arduino/python/main.py` to that path on the board (or point
`ExecStart` at wherever you placed it).

```sh
export XDG_RUNTIME_DIR=/run/user/1000
systemctl --user status strokesense-ble.service
journalctl --user -u strokesense-ble.service -o cat -f
```

A clean bring-up logs `BLE advertising as StrokeSense` and `MCU link live: ANG,...`.
The advertised name is `StrokeSense`. This board's MAC is `14:B5:CD:F3:98:71` (per-board;
rescan on other hardware).

### Phone: Mock vs Uno Q BLE

Make sure the board is powered and `strokesense-ble.service` is advertising before scanning.

1. **Settings** (gear icon on Home) → scroll to **Arduino IMU**.
2. Tap **Uno Q BLE** (or **Mock data**). There is one exclusive choice — no
   separate "Use mock sensors" switch. Tapping BLE persists `mockSensors=false`
   and `sensorTransport=BLE` together (`SettingsStore.setSensorSource`).
3. Tap **Scan for StrokeSense** and grant Bluetooth permission if asked. Matching
   advertisers appear as chips showing name, MAC and RSSI — tap the board.
   Or paste the MAC **`14:B5:CD:F3:98:71`** (this board) into **Board MAC**.
4. Tap **Connect to board**. The green banner should read **Connected · StrokeSense**
   with the MAC. **Disconnect** tears the link down; **Reconnect** on the motor
   screens opens it again.
5. Go to **Motor / balance** from Home. Motor calibration and Motor test both show
   the same connection banner, live **Left / Right / Diff** cards (dashed when
   not connected), a live chart, Arduino cue controls, and a
   filled silhouette at the **bottom** of the screen (see below).
6. On the **Arduino cue** card: set cooldown minutes and tap **Send cooldown to
   Arduino** (`SETCOOLDOWN`), optionally set **Sleep hours** and tap **Send sleep
   hours** (`SETSLEEP`), then **Start arm test** (`START`) with **Stop** beside it
   (`STOP`).
7. Raise both arms until they match. The board's buzzers stop, then wait the cooldown
   before rearming.

The motor screens also **auto-scan when no MAC is saved**, save whatever they find, and
show a persistent banner (not a small Status row): green **Connected · StrokeSense**
plus MAC when live, amber **Connecting…** / **Scanning…**, red **Not connected** /
**Disconnected**, gray **Mock data**. **Reconnect** and **Disconnect** sit together
whenever BLE is selected. **Disconnect** sends `STOP`, then tears down GATT
(`SensorRepository.stopAndDisconnect()`). The same teardown runs when you leave the
screen (`AssessmentViewModel.releaseSensor`). Settings has the same banner and
Disconnect control.

### Motor silhouette (`BodyFigure.kt` / `BodyPoseFigure`)

A **minimal filled silhouette** sits at the **bottom** of Motor calibration and
Motor test (`ArmPoseSection`). It is authored in a **3:5** box and always scaled
uniformly, so Expand cannot stretch it to the window:

- teal left arm, green right arm, muted body;
- arm elevation: **0° hanging down**, **~60° horizontal** (firmware's full raise;
  anything past 60° lifts a little further);
- a small coral **Δ** when the left/right difference is **≥ 8°** (no leader lines).

**Expand** centers the same 3:5 figure in a full-screen dialog. **Close** or
system back collapses it.

### Sleep hours and cooldown

Quiet hours are a user-set **start** and **end** (overnight windows are valid;
times default to **22:00–07:00**, off until you send them). The phone sends
`SETSLEEP,HH:MM,HH:MM` or `SETSLEEP,OFF`. The STM32 has no RTC, so the Linux host enforces the window on its
wall clock and drives an MCU `SLEEP,1` latch that blocks the buzzers. Cooldown is
`SETCOOLDOWN,<minutes>` (0.01–1440). Start/Stop are `START` / `STOP`. The saved
cooldown and sleep window are re-pushed on every fresh link.

### Bluetooth permissions

BLE *scanning* is stricter than connecting:

| Android | Runtime permissions needed to scan |
| --- | --- |
| API 26–30 (8.0–11) | `ACCESS_FINE_LOCATION` — without it the scanner silently returns **no results** |
| API 31+ (12 and up) | `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` |

The app requests the right set per API level (`sensor/BluetoothDevices.kt`) and surfaces
"Turn Bluetooth on" separately from a permission problem.

### What the motor module actually measures

The board streams mapped pitch per hand (`0` = arm down, higher = raised) plus the
absolute difference, at roughly 7 Hz:

```
ANG,L,45.2,R,43.1,DIFF,2.1
```

Scoring is based on **sustained left/right asymmetry** — a weighted blend of mean
difference and peak difference over the collected window — replacing the older
accelerometer-magnitude drift/tremor heuristic. The calibration screen records a 3 s
baseline (60 samples) of left / right / difference. A missing sensor arrives as `nan` and
is skipped rather than treated as zero.

### No hardware?

Stay on **Mock data** in Settings → Arduino IMU (do not tap **Uno Q BLE**).
`MockSensorSource` synthesises a plausible two-arm raise, and the motor test screen has
a **Simulate uneven arm raise** switch that makes the left arm lag so a positive screen
can be demoed.

### Cold-boot recovery is set up but unvalidated

On the board side, everything needed for the BLE bridge to come back by itself after a
power-bank cold boot is configured: host unit `strokesense-ble.service` (not App Lab
"run at startup"), `loginctl enable-linger arduino`, the unit `enable`d, and
`Restart=always`. **This was never empirically validated** — nobody confirmed a full
unattended battery cold boot ending in a successful phone connection. Budget time to
power-cycle the board and re-check before demoing on battery.

---

## Speech transcription & Deepgram

The app streams audio to the **backend Deepgram proxy** by default
(`wss://work.tail043976.ts.net/v1/deepgram/stream`), so the Deepgram API key lives
on the gx10 server (`.env` → `DEEPGRAM_API_KEY`) and **never ships in the APK**.

- Per-device proxy override: Settings → **Speech transcription** → Deepgram
  proxy URL, or `local.properties` → `DEEPGRAM_PROXY_URL` (defaults to the live
  gateway). The direct API-key field is intentionally disabled while proxy mode
  is the default.
- Direct-to-Deepgram fallback is only used when no proxy is configured: set the
  key in the app (Settings → Speech transcription) or in the gitignored
  `local.properties` → `DEEPGRAM_API_KEY`.
- Build-time defaults (gitignored `local.properties`, exposed as `BuildConfig`):

  ```properties
  DEEPGRAM_API_KEY=your_key_here
  DEEPGRAM_PROXY_URL=wss://host/v1/deepgram/stream
  GATEWAY_BASE_URL=https://host
  GATEWAY_TOKEN=shared_demo_token
  ```

  These are used when nothing is saved in Settings. **Do not commit real values**
  — `gradle.properties` deliberately leaves `GATEWAY_TOKEN` empty.
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
  optional SMS to an emergency contact (configure in **Settings → Emergency SMS**).
  The SMS has a 15 s cancel window to protect against false positives.

Required permissions: `RECORD_AUDIO`, `POST_NOTIFICATIONS` (Android 13+), and
`SEND_SMS` (only if SMS alerts are enabled).

Tune sensitivity with the slider on the monitor screen (higher = more sensitive).

---

## Tests

JVM unit tests cover the DSP, detector and sensor parsing (no device needed):

```bash
./gradlew :app:testDebugUnitTest
```

- `audio/DspTest` — pitch, jitter/shimmer, RMS, ZCR, spectral centroid and FFT on
  synthetic signals.
- `audio/SlurDetectorTest` — baseline comparison and alerting.
- `sensor/ArmAngleParserTest` — `ANG,...` frames, `nan` handling, the legacy
  `L 45.2  R 43.1` serial form, and ignoring firmware debug lines.
- `sensor/BleScanMatchTest` — matching the board by service UUID *or* by local name, since
  BlueZ may drop the UUID list to fit the 31-byte legacy advertising payload.

---

## Care alerts with Linq

The results screen can send a concise screening summary to a trusted contact and,
for a high-concern result, offers a **Call emergency services** button. The call
button only opens Android's dialer after a confirmation tap; the app never places
an emergency call automatically.

Configure these in Settings → **Care alerts** and **Backend gateway**:

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
`LINQ_API_TOKEN`; do not add it to Android's `local.properties`, Settings, or any
tracked source file. The Android **Gateway token** field is only for that
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

In the app, **Settings → Backend gateway / Care alerts**:

- Gateway URL: `https://work.tail043976.ts.net/v1/stroke-alerts`
- Gateway token: the shared demo token (kept in `local.properties`, not here)
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
  ui/            theme, nav graph (StrokeApp.kt), AssessmentViewModel, screens/,
                 components/ (BodyFigure.kt filled silhouette, SleepHoursEditor)
  domain/        Assessment, ModuleResult, Metric, RiskBand, MonitorModels
  video/         FaceAnalyzer, AsymmetryCalculator, FaceServer (client),
                 FaceAnalysisController
  audio/         AudioCapture, DeepgramClient, DeepgramStream, Vad, Dsp,
                 AcousticFeatureExtractor, SlurDetector, SlurServer (client),
                 SpeechAnalyzer, SpeechSession, SpeechMonitor, Tts, Speaker, Wav
  alerts/        AlertManager (notifications, auto-FAST, SMS)
  alert/         alert drafts, summary factory, secure Linq gateway client, repository
  sensor/        SensorSource (+ SensorSample, ANG parser), MockSensorSource,
                 BleSensorSource (UNO Q Nordic UART), BleScanner (scan + match),
                 BluetoothDevices (per-API permissions), SleepWindow,
                 SensorRepository (MOCK / BLE only; stale BLUETOOTH_SPP / WIFI → BLE)
  scoring/       StrokeRiskScorer (weighted FAST bands)
  data/          SettingsStore, BaselineStore
app/src/test/java/com/hackmit/app/
  audio/         DspTest, SlurDetectorTest
  sensor/        ArmAngleParserTest, BleScanMatchTest
backend/         FastAPI care-alert gateway + Deepgram proxy + local voice
tools/rename.sh
tools/slur_eval/ offline Python port of SlurDetector + dysarthria-corpus evaluation
arduino/         UNO Q firmware (App Lab): sketch/sketch.ino, python/main.py,
                 systemd/strokesense-ble.service — onboard BLE, not HC-05
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

1. Validate board cold-boot recovery on battery, end to end, with no PC attached.
2. Tune the motor asymmetry thresholds against real arm-raise recordings; the
   current mean/peak difference weights are hand-picked.
3. Add a foreground service so monitoring survives backgrounding.
4. Provision a Linq phone number so care-alert delivery works end-to-end.
5. Calibrate the server AI slur score on phone-mic audio (domain adaptation), so
   normal phone speech isn't over-flagged.
6. Tune slur thresholds with recorded normal vs. slurred clips (a WAV replay
   harness is the next testing addition).
