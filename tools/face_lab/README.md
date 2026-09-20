# face_lab — face + audio proof-check lab

Streams your webcam to the gateway's **`/v1/face/debug`** route and shows, in a
native `ffplay` window, exactly what the server (MediaPipe FaceLandmarker + the
trained LR) is doing with your face — plus a mic panel that scores your speech
with the real WavLM `/v1/slur/analyze` endpoint.

It mirrors the app's face path exactly: 640×480 capture → JPEG **maxDim 480,
quality 60** → uploaded **every 600 ms, single in-flight** → same server model.

## One-time setup
```bash
cd tools/face_lab
uv venv --python 3.12
uv pip install --python .venv/bin/python opencv-python-headless numpy requests
# face debug gate + token must be on in the gateway (.env):
#   FACE_DEBUG_ENABLED=true
#   FACE_DEBUG_TOKEN=<hex>
printf 'FACE_DEBUG_TOKEN=%s\n' "<token from gx10 .env>" > .env   # gitignored
```
`GATEWAY_BASE_URL` / `GATEWAY_TOKEN` are read from the repo's `local.properties`.

## Run
```bash
.venv/bin/python lab.py                 # window: landmarks + score + audio panel
.venv/bin/python lab.py --print         # also log a HUD line per analyzed frame
.venv/bin/python lab.py --no-audio      # face only
.venv/bin/python lab.py --frames 10     # exit after 10 analyzed frames
.venv/bin/python lab.py --bench         # latency sweep (res × jpeg quality)
```

## What the window shows
- **All 478 landmarks** (faint) + the **key indices** highlighted (`eye 33/263`,
  `mouth 61/291`, `cheek 234/454`, `brow 105/334`, lids).
- The **eye-line axis** (yellow) and the **perpendicular `v` axis** (blue) through
  the eye midpoint — the geometry the features are built from.
- HUD: the four features, their z-scores, logit, temperature, `score`,
  `score_cal`, `quality`, threshold, JPEG KB, RTT, `server_ms`, analyzed FPS.
- Audio panel: mic waveform + level, and the WavLM `score` / `score_cal` / `mode`
  / `ood` / `detected`.

## Benchmark (measured on the integrated camera)
| dim | q | KB | server p50 | RTT p50 | RTT p95 | serial fps |
|---|---|---|---|---|---|---|
| 320 | 50 | 4 | 14.7 ms | 113 ms | 152 ms | 5.7 |
| 480 | 60 | 9 | 14.9 ms | 129 ms | 138 ms | 7.2 |
| 720 | 60 | 17 | 8.0 ms | 127 ms | 162 ms | 7.0 |

Server compute is ~8–17 ms; the network RTT (~110–135 ms, public Funnel) is the
bottleneck. The app only needs 1.6 fps (600 ms throttle), so there is large
headroom.

## Notes
- Debug route is token-gated and **disabled by default** (`FACE_DEBUG_ENABLED`).
  Turn it off when you're done (`FACE_DEBUG_ENABLED=false`, restart the service).
- The production `/v1/face/analyze` is unchanged; the app never calls `/debug`.
- Audio is captured with `ffmpeg -f pulse -i default` (PipeWire compatible).
