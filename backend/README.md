# StrokeSense Alert Gateway

A small FastAPI service that the Android app posts screening summaries to. It
owns the **Linq Partner API** integration token, enforces a recipient allowlist,
deduplicates retries, and sends the caregiver message over iMessage/RCS/SMS.

The Android client (`app/src/main/java/com/hackmit/app/alert/LinqAlertGateway.kt`)
never sees the Linq token. It only knows the gateway URL and a shared
`X-Alert-Gateway-Token`.

## Live deployment (HackMIT demo)

- **Public URL:** `https://work.tail043976.ts.net` via Tailscale Funnel.
  Funnel is public internet access — anyone can reach it, no Tailscale account.
- **Endpoint:** `POST https://work.tail043976.ts.net/v1/stroke-alerts`
- **Auth:** `X-Alert-Gateway-Token` header (shared demo token, shared out-of-band).
- **Allowlist:** `ALLOWED_RECIPIENTS=*`, so each app user picks their own
  trusted contact in-app.
- **App settings → Care alerts (Linq):** gateway URL
  `https://work.tail043976.ts.net/v1/stroke-alerts`, the shared token, and your
  contact number (E.164).

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/healthz` | Liveness, config status, DB counters |
| `POST` | `/v1/stroke-alerts` | Accepts the alert envelope, sends via Linq |
| `WS` | `/v1/deepgram/stream` | Deepgram proxy (auth: `Authorization: Token <gateway_token>`) |
| `POST` | `/v1/webhooks/linq` | Linq webhook receiver (delivery/status events) |
| `POST` | `/v1/linq/webhook-subscription` | Registers the webhook with Linq (token-protected) |
| `POST` | `/v1/tts` | Local TTS (Kokoro, fallback Piper) → WAV |
| `POST` | `/v1/agent` | Local voice agent (Ollama) Q&A |
| `POST` | `/v1/speaker/enroll` | Enroll the user's voice for gating (raw PCM/WAV) |
| `POST` | `/v1/face/analyze` | Server-side face asymmetry (MediaPipe + LR) |
| `POST` | `/v1/slur/analyze` | Server-side slur score (WavLM embedding + LR) |
| `GET` | `/v1/speaker/status` | Gating status + enrollment state |

### Voice (TTS + agent)
- `POST /v1/tts` `{"text": "...", "voice": "af_heart"}` → `audio/wav`. Kokoro runs
  on gx10; model files cache under `models/tts`. Falls back to Piper if Kokoro fails.
- `POST /v1/agent` `{"question", "task_context", "history"}` → Ollama
  (`OLLAMA_MODEL`) with task-aware system prompt. The Android app uses it for
  personalized results and task Q&A.

### Speaker gating
`POST /v1/speaker/enroll` takes ≥1 s of 16 kHz PCM (raw or WAV); the gateway
computes an ECAPA embedding (`speechbrain/spkrec-ecapa-voxceleb`) and stores it
at `SPEAKER_EMBEDDING_PATH`. When `SPEAKER_GATE_ENABLED=true`, the Deepgram proxy
only forwards speech windows whose embedding matches the enrolled speaker
(cosine ≥ `SPEAKER_THRESHOLD`); everyone else is dropped on gx10 — their audio
never reaches Deepgram.

Request body (from the app):

```json
{
  "alert_id": "uuid",
  "source": "assessment",
  "kind": "emergency_recommended",
  "severity": "urgent",
  "title": "URGENT: StrokeSense recommends emergency help",
  "body": "Screening score: 72%...",
  "created_at_ms": 1700000000000,
  "recipient": { "name": "Caregiver", "phone": "+15551234567" }
}
```

Headers: `Idempotency-Key: <alert_id>`, optional `X-Alert-Gateway-Token`.
Response: `{"status": "sent", "trace_id": "linq:<chat_id>:<message_id>"}`.

Sent alerts are recorded in SQLite (`DB_PATH`) for audit and idempotency across
restarts and uvicorn workers (WAL mode). Incoming Linq webhooks are stored in
`webhook_events`.

### Deepgram proxy

The APK streams audio to `WS /v1/deepgram/stream?...<deepgram query params>` with
`Authorization: Token <gateway_token>`. The gateway validates the token, connects
to Deepgram with its own `DEEPGRAM_API_KEY`, and relays frames/events both ways.
The client's query parameters (model, encoding, etc.) are forwarded to Deepgram.

## Configuration

Copy `.env.example` to `.env` and set:

- `LINQ_API_TOKEN` — Linq integration token (server-side only)
- `LINQ_FROM_NUMBER` — a number provisioned in your Linq organization (E.164)
- `LINQ_PUBLIC_BASE_URL` — public base URL for the webhook receiver (e.g. `https://work.tail043976.ts.net`)
- `DEEPGRAM_API_KEY` — Deepgram key used by the proxy (server-side only)
- `GATEWAY_TOKEN` — shared secret; must match the app's *Alert gateway token*
- `ALLOWED_RECIPIENTS` — comma-separated E.164 allowlist, or `*` to allow any
  recipient the app sends (each user sets their own contact in-app). Empty rejects all.
- `WEBHOOK_SECRET` — optional secret to verify Linq webhook signatures
- `DB_PATH` — SQLite file (default `strokesense.db`)

`LINQ_FROM_NUMBER` must exist in your organization; check with:

```bash
curl -s https://api.linqapp.com/api/partner/v2/phone_numbers \
  -H "X-LINQ-INTEGRATION-TOKEN: $LINQ_API_TOKEN"
```

## Run locally

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env   # fill it in
.venv/bin/uvicorn gateway.main:app --host 127.0.0.1 --port 8000
```

## Deploy on gx10 (user systemd + Tailscale Funnel)

```bash
./deploy/install.sh                       # copies to ~/strokesense-gateway, venv, user unit
$EDITOR ~/strokesense-gateway/.env        # set LINQ_FROM_NUMBER, GATEWAY_TOKEN, ALLOWED_RECIPIENTS
systemctl --user restart strokesense-gateway
sudo loginctl enable-linger "$USER"       # survive logout/reboot (one-time)
tailscale funnel --bg 8000                # public https://<node>.<tailnet>.ts.net/
```

Then in the app: **Settings → Care alerts (Linq)** with
`https://<node>.<tailnet>.ts.net/v1/stroke-alerts` and the same `GATEWAY_TOKEN`.

Funnel is public; the token and recipient allowlist are the access controls.
Rotate `GATEWAY_TOKEN` and the Linq token if either leaks.
