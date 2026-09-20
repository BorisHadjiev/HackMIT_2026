# StrokeSense — Plume submission (updated)

Paste these into the matching fields. Key fixes vs the current page:
1) We do NOT auto-call 911 — we alert a trusted caregiver and offer one-tap 911 (safer + honest).
2) Analysis runs on the **GX10 edge box** (private/local), not "on the phone".
3) Added the real AI, the risk decision layer, validation numbers, and the simulated-911 practice.

---

## Tagline (<= 120 chars)
Smart wristbands + your phone screen for stroke signs and connect you to help fast — caregiver alert, one-tap 911.

## Project thumbnail
Use a shot of the two wristbands + the phone showing the live FAST score. (Replace the current generic thumbnail.)

## Inspiration
A stroke can become a medical emergency within minutes, and many people are alone when symptoms first appear —
1 in 4 people will have a stroke in their lifetime. We kept coming back to the same gap: the signs are simple
(the FAST test), yet most wearables do nothing about them. StrokeSense is our answer: continuous, passive
monitoring that only escalates when multiple signals agree, and that reaches a real person — not just a dashboard.

## What it does
StrokeSense watches for stroke signs without the user having to remember a test:
- **Arm** — an hourly bilateral arm-raise check from the wristbands; the IMUs compare left/right motion and drift.
- **Only if that fails**, it runs the rest of the FAST check: **face** (camera → facial-asymmetry score) and
  **speech** (mic → slur score, plus a short voice check with a local voice agent that talks to the user).
- **Risk layer** — the results are fused into a stroke likelihood, a severity estimate, and an **action**:
  **Call 911 / Alert caregiver / Keep monitoring**, using clinically-informed rules (CPSS/ROSIER/FAST-ED) with a
  safety rule that never downgrades a clinically positive result.
- **Gets help** — it sends a concise screening summary to a trusted contact by **SMS/iMessage/RCS** with delivery
  confirmation, and shows a prominent **one-tap 911** (it never dials emergency services by itself).
- **Privacy-first, not fully offline** — speech and facial **analysis**, text-to-speech and the language model run
  locally on an edge computer, so the screening decision never depends on a cloud service. Live transcription uses
  Deepgram, and the caregiver alert is delivered via Linq or the phone's own SMS.
- **Practice mode** — a simulated 911 call lets you rehearse reporting symptoms to a dispatcher (clearly labelled a
  simulation).

## How we built it
**Hardware**
- Arduino UNO Q (system hub), 2 Velcro wristbands with 6-DOF accelerometers + gyroscopes, camera, speaker, mic,
  vibration motors, Li-ion battery, Bluetooth.

**Software / AI** (Android app + a local edge gateway on an ASUS GX10 AI Supercomputer)
- **Android app** (Kotlin, Jetpack Compose): the guided FAST flow, continuous monitoring, the risk result, the
  caregiver alert and the one-tap dialer.
- **Face**: MediaPipe FaceLandmarker → 4 geometry features → logistic regression (5-fold CV **AUC 0.84**).
- **Speech**: WavLM self-supervised embeddings → logistic regression; on public dysarthria data it reaches
  **AUC 0.945–0.997** (speaker-disjoint). Crucially, it scores against the **wearer's own calibrated voice**: we
  shift the live embedding onto the corpus healthy centroid (a per-user domain calibration) so normal speech scores
  ~0 and real slur scores ~1.
- **Voice check**: local **Kokoro** text-to-speech and a local **Ollama** language model so the agent can talk with
  the user. The live monitor transcribes via **Deepgram** (streaming); local **Whisper** is available on the box.
- **Get help**: **Linq** sends the caregiver alert (SMS/iMessage/RCS) with delivery receipts and idempotency, via a
  FastAPI gateway that keeps all API keys server-side.
- **Flow**: wristbands → Arduino hub → app → (GX10 edge inference) → risk layer → caregiver alert / one-tap 911.
- **What runs where**: face/slur models, TTS, the LLM and the risk layer run on the **local edge box**; **Deepgram**
  handles live transcription (and the simulated-911 agent), and **Linq** (or device SMS) delivers the caregiver alert.

## Individual Contributions
(Verify names/roles; kept from the original with concrete artifacts added.)
- **Ryan** — edge AI inference on the GX10 (dysarthria + facial-asymmetry models, the per-user voice baseline, and
  the gating logic that only triggers face/speech after a failed arm-raise).
- **Boris** — embedded hardware on the Arduino UNO Q (IMUs, arm-raise/drift detection, vibration + speaker feedback,
  hub aggregation).
- **Alex** — emergency alerting pipeline (the SMS/text interface and the conditions required before an alert; backend
  pipeline).
- **Ahmad** — the app/interface (system state, check-in, event history, demos) and the model-evaluation workflow.

## Challenges we ran into
- **Integrating everything** — sensors, Bluetooth, Arduino, camera, mic, speakers, AI models and emergency
  messaging into one wearable is far harder than any single component.
- **False positives** — we refuse to alert on one abnormal reading; layered, multi-signal decision-making with
  sensor history and a calibrated personal voice baseline is what makes it usable.
- **Domain shift** — our speech model first scored normal *phone* audio as 100% slurred; per-user calibration fixed it.
- **Voice feedback** — the voice agent initially heard its own speaker and answered itself; echo cancellation plus a
  mic gate while it speaks fixed it.
- **Local inference** — running private AI on-device (the GX10) meant making the models fast enough and keeping all
  keys/data on the edge.

## What we learned
Integrating a physical AI system is much harder than building its parts; layered decision-making beats a single
sensor; and healthcare tech needs privacy, reliability and ease of use as much as model accuracy.

## What's next
- A real **voice-call alert** with a generic spoken script (caregiver), still confirm-only.
- Validate with outcome-linked data (today we use honest public proxies), then fine-tune on phone-domain recordings.
- Shrink the hardware and extend F.A.S.T. → **B.E. F.A.S.T.** (balance, eyes).
- A **fully offline** option: route live transcription to on-box Whisper and deliver the caregiver alert via device SMS,
  so no third party is involved at all.

## Links
- GitHub: https://github.com/BorisHadjiev/HackMIT_2026
- Demo video: `<add a 60–90s Loom/YouTube link>`

## Honesty note (keep in the description)
StrokeSense is a **screening aid, not a medical device** and is not clinically validated. It recommends and
requires confirmation, and **never places an emergency call automatically**.

## Sponsor challenges we genuinely hit (mention where relevant)
- **ASUS GX10** — local edge inference for face + speech + LLM + TTS (privacy-first).
- **Deepgram** — streaming speech-to-text behind a voice agent that actually talks to the user.
- **Arduino** — the wristband/hub hardware that senses the real world.
- **Voloridge "Signal in the Noise"** — multi-signal fusion + calibration to separate real warning signs from noise.
- **Ramp "Save Time. Save Money."** — time-critical triage; local models avoid per-call API cost.

## Fill the media slot
Upload 3–5 screenshots (app Home, live monitoring with a personal-mode AI score, the risk result with the action,
the delivered caregiver message, the simulated-911 transcript) and a 60–90 second demo video.
