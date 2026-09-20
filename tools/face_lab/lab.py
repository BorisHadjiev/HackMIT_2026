#!/usr/bin/env python3
"""Face + audio proof-check lab for StrokeSense.

Streams camera frames to the gateway's /v1/face/debug route and shows, in a native
ffplay window, exactly what the server (MediaPipe + the LR) is doing with your face:
all 478 landmarks, the key indices, the eye-line axis and perpendicular offsets, the
four features, the z-scores, logit, temperature, and the final probability — plus a
latency HUD and a live audio panel (mic level + WavLM slur score).

Run:  .venv/bin/python lab.py
Bench: .venv/bin/python lab.py --bench
"""
from __future__ import annotations

import argparse
import io
import os
import subprocess
import sys
import threading
import time
import wave
from collections import deque

import cv2
import numpy as np
import requests

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SLUR_EVAL = os.path.join(REPO, "tools", "slur_eval")
sys.path.insert(0, SLUR_EVAL)

# Key indices mirrored from backend/gateway/face.py (for coloring/labels).
KEY_LABELS = {
    33: "eye_l", 263: "eye_r",
    61: "mouth_l", 291: "mouth_r",
    234: "cheek_l", 454: "cheek_r",
    105: "brow_l", 334: "brow_r",
    159: "lid_ul", 145: "lid_ll", 386: "lid_ur", 374: "lid_lr",
}
PAIRS = [("mouth", 61, 291), ("cheek", 234, 454), ("brow", 105, 334)]


# ---------------------------------------------------------------- config
def read_props(path: str) -> dict:
    props: dict[str, str] = {}
    if not os.path.exists(path):
        return props
    for line in open(path):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
    return props


def load_config() -> tuple[str, str, str]:
    props = read_props(os.path.join(REPO, "local.properties"))
    props.update(read_props(os.path.join(os.path.dirname(__file__), ".env")))
    base = os.environ.get("GATEWAY_BASE_URL") or props.get("GATEWAY_BASE_URL", "")
    token = os.environ.get("GATEWAY_TOKEN") or props.get("GATEWAY_TOKEN", "")
    debug = os.environ.get("FACE_DEBUG_TOKEN") or props.get("FACE_DEBUG_TOKEN", "")
    return base.rstrip("/"), token, debug


# ---------------------------------------------------------------- audio
class AudioPanel:
    """Captures mic via ffmpeg (PulseAudio/PipeWire) and scores it on the gateway."""

    def __init__(self, base: str, token: str, interval_s: float = 4.0, buffer_s: float = 20.0) -> None:
        self.base, self.token, self.interval_s = base, token, interval_s
        self.rate = 16000
        self.max_samples = int(buffer_s * self.rate)
        self.buf: deque[int] = deque(maxlen=self.max_samples)
        self.lock = threading.Lock()
        self.proc: subprocess.Popen | None = None
        self.latest: dict | None = None
        self.error: str | None = None
        self._stop = threading.Event()
        self._last_post = 0.0
        self.enabled = bool(base and token)

    def start(self) -> None:
        if not self.enabled:
            self.error = "no gateway config"
            return
        cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "pulse",
               "-i", "default", "-ar", str(self.rate), "-ac", "1", "-f", "s16le", "-"]
        try:
            self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except FileNotFoundError:
            self.error = "ffmpeg not found"
            return
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self) -> None:
        assert self.proc and self.proc.stdout
        while not self._stop.is_set():
            chunk = self.proc.stdout.read(4096)
            if not chunk:
                break
            samples = np.frombuffer(chunk, dtype=np.int16)
            with self.lock:
                self.buf.extend(int(s) for s in samples)
        if self.proc and self.proc.poll() is None:
            self.error = "audio capture ended"

    def level(self) -> float:
        with self.lock:
            if len(self.buf) < 1600:
                return 0.0
            tail = np.fromiter(list(self.buf)[-1600:], dtype=np.int16)
        return float(np.sqrt(np.mean(tail.astype(np.float64) ** 2)) / 32768.0)

    def waveform(self, n: int = 400) -> np.ndarray:
        with self.lock:
            data = np.fromiter(list(self.buf), dtype=np.int16)
        if data.size < n:
            return np.zeros(n, dtype=np.int16)
        idx = np.linspace(0, data.size - 1, n).astype(int)
        return data[idx]

    def maybe_score(self) -> None:
        if not self.enabled or self.proc is None:
            return
        now = time.time()
        if now - self._last_post < self.interval_s:
            return
        self._last_post = now
        with self.lock:
            data = np.fromiter(list(self.buf)[-self.rate * 4:], dtype=np.int16)
        if data.size < self.rate * 2:
            return
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(self.rate)
            w.writeframes(data.tobytes())
        threading.Thread(target=self._post, args=(buf.getvalue(),), daemon=True).start()

    def _post(self, wav: bytes) -> None:
        try:
            r = requests.post(
                f"{self.base}/v1/slur/analyze",
                data=wav,
                headers={"X-Alert-Gateway-Token": self.token, "Content-Type": "audio/wav"},
                timeout=30,
            )
            if r.ok:
                self.latest = r.json()
        except requests.RequestException as exc:  # noqa: BLE001
            self.error = str(exc)

    def stop(self) -> None:
        self._stop.set()
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()


# ---------------------------------------------------------------- face
def encode_jpeg(frame: np.ndarray, max_dim: int, quality: int) -> bytes:
    h, w = frame.shape[:2]
    if max(h, w) > max_dim:
        s = max_dim / max(h, w)
        frame = cv2.resize(frame, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
    ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, quality])
    if not ok:
        raise RuntimeError("jpeg encode failed")
    return buf.tobytes()


def post_face(base: str, token: str, debug_token: str, jpeg: bytes) -> tuple[dict | None, float]:
    t0 = time.perf_counter()
    try:
        r = requests.post(
            f"{base}/v1/face/debug",
            data=jpeg,
            headers={
                "X-Alert-Gateway-Token": token,
                "X-Face-Debug-Token": debug_token,
                "Content-Type": "image/jpeg",
            },
            timeout=15,
        )
        rtt_ms = (time.perf_counter() - t0) * 1000.0
        if r.ok:
            return r.json(), rtt_ms
        return {"detected": False, "error": f"HTTP {r.status_code}"}, rtt_ms
    except requests.RequestException as exc:  # noqa: BLE001
        return {"detected": False, "error": str(exc)}, (time.perf_counter() - t0) * 1000.0


def annotate(frame: np.ndarray, res: dict, hud: list[str], audio: AudioPanel) -> np.ndarray:
    h, w = frame.shape[:2]
    out = frame.copy()
    if res.get("detected") and res.get("landmarks"):
        lms = res["landmarks"]
        pts = [(int(x * w), int(y * h)) for x, y in lms]
        for (x, y) in pts:
            cv2.circle(out, (x, y), 1, (90, 90, 90), -1, lineType=cv2.LINE_AA)
        # eye-line axis
        g = res.get("geometry") or {}
        if 33 in KEY_LABELS and len(pts) > 454:
            cv2.line(out, pts[33], pts[263], (0, 220, 255), 1, cv2.LINE_AA)
        # perpendicular axis through mid
        if g.get("mid") and g.get("v"):
            mx, my = int(g["mid"][0] * w), int(g["mid"][1] * h)
            vx, vy = g["v"]
            L = 60
            cv2.line(out, (int(mx - vx * L), int(my - vy * L)), (int(mx + vx * L), int(my + vy * L)),
                     (255, 120, 0), 1, cv2.LINE_AA)
        # key indices + pairs
        for idx, label in KEY_LABELS.items():
            if idx < len(pts):
                x, y = pts[idx]
                cv2.circle(out, (x, y), 3, (0, 0, 255), -1, lineType=cv2.LINE_AA)
        for name, a, b in PAIRS:
            if a < len(pts) and b < len(pts):
                cv2.line(out, pts[a], pts[b], (0, 255, 0), 1, cv2.LINE_AA)

    # HUD (left)
    y = 18
    for line in hud:
        cv2.putText(out, line, (8, y), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (0, 0, 0), 2, cv2.LINE_AA)
        cv2.putText(out, line, (8, y), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (255, 255, 255), 1, cv2.LINE_AA)
        y += 16

    # audio panel (right)
    pw = 210
    x0 = w - pw
    overlay = out.copy()
    cv2.rectangle(overlay, (x0, 0), (w, 150), (20, 20, 20), -1)
    cv2.addWeighted(overlay, 0.55, out, 0.45, 0, out)
    wave_data = audio.waveform(180)
    base_y = 70
    for i in range(1, len(wave_data)):
        x1 = x0 + 8 + int((i - 1) / len(wave_data) * (pw - 16))
        x2 = x0 + 8 + int(i / len(wave_data) * (pw - 16))
        cv2.line(out, (x1, base_y - int(wave_data[i - 1] / 32768 * 40)),
                 (x2, base_y - int(wave_data[i] / 32768 * 40)), (0, 255, 180), 1, cv2.LINE_AA)
    lvl = audio.level()
    cv2.rectangle(out, (x0 + 8, 120), (x0 + 8 + int((pw - 16) * min(1.0, lvl * 8)), 128), (0, 255, 180), -1)
    cv2.rectangle(out, (x0 + 8, 120), (w - 8, 128), (120, 120, 120), 1)
    ares = audio.latest or {}
    a_lines = [
        "AUDIO (mic)",
        f"level {lvl * 100:4.1f}%",
        f"WavLM {ares.get('score', '—')}  mode {ares.get('mode', '—')}",
        f"cal {ares.get('score_cal', '—')}  ood {ares.get('ood', '—')}",
        f"detected {ares.get('detected', '—')}",
        (audio.error or "")[:28],
    ]
    yy = 16
    for line in a_lines:
        cv2.putText(out, line, (x0 + 8, yy), cv2.FONT_HERSHEY_SIMPLEX, 0.36, (255, 255, 255), 1, cv2.LINE_AA)
        yy += 15
    return out


def start_ffplay(width: int, height: int) -> subprocess.Popen | None:
    cmd = ["ffplay", "-hide_banner", "-loglevel", "error", "-fflags", "nobuffer",
           "-flags", "low_delay", "-f", "rawvideo", "-pixel_format", "bgr24",
           "-video_size", f"{width}x{height}", "-i", "-",
           "-window_title", "StrokeSense face lab"]
    try:
        return subprocess.Popen(cmd, stdin=subprocess.PIPE)
    except FileNotFoundError:
        print("ffplay not found — install ffmpeg or use --no-window", file=sys.stderr)
        return None


def run_live(args: argparse.Namespace) -> int:
    base, token, debug_token = load_config()
    if not base or not token:
        print("Missing GATEWAY_BASE_URL / GATEWAY_TOKEN (local.properties or env).", file=sys.stderr)
        return 2
    if not debug_token:
        print("Missing FACE_DEBUG_TOKEN (env or tools/face_lab/.env).", file=sys.stderr)
        return 2

    cap = cv2.VideoCapture(args.camera, cv2.CAP_V4L2)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, args.width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, args.height)
    if not cap.isOpened():
        print(f"camera {args.camera} not available", file=sys.stderr)
        return 2

    audio = AudioPanel(base, token) if not args.no_audio else None
    if audio:
        audio.start()

    player = start_ffplay(args.width, args.height)
    print(f"face lab → {base}/v1/face/debug  (Ctrl-C to quit)")
    frames = 0
    shown = 0
    t_start = time.time()
    next_at = 0.0
    last_res: dict = {}
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if args.frames and frames >= args.frames:
                break
            now = time.time()
            if now >= next_at:
                next_at = now + args.interval_ms / 1000.0
                jpeg = encode_jpeg(frame, args.max_dim, args.quality)
                res, rtt = post_face(base, token, debug_token, jpeg)
                last_res = res
                if res.get("detected"):
                    shown += 1
                elapsed = time.time() - t_start
                fps = (frames + 1) / elapsed if elapsed else 0.0
                g = res.get("geometry") or {}
                feats = res.get("feats") or {}
                hud = [
                    f"detected={res.get('detected')}  q={res.get('quality')}  thr={res.get('threshold')}",
                    f"score={res.get('score')}  score_cal={res.get('score_cal')}  logit={res.get('logit')}",
                    f"mouth={feats.get('mouth_perp_abs')}  eye={feats.get('eye_open_asym')}",
                    f"cheek={feats.get('cheek_perp_abs')}  brow={feats.get('brow_perp_abs')}",
                    f"z={res.get('z')}  T={res.get('temperature')}",
                    f"jpeg={len(jpeg) // 1024}KB  rtt={rtt:5.1f}ms  server={res.get('server_ms')}ms",
                    f"landmarks={res.get('landmark_count')}  analyzed_fps={fps:4.2f}  shown={shown}",
                ]
                if args.print:
                    print(" | ".join(hud))
                if audio:
                    audio.maybe_score()
                frames += 1
                frame = annotate(frame, last_res, hud, audio) if audio else annotate(frame, last_res, hud, _DummyAudio())
                if player and player.stdin:
                    try:
                        player.stdin.write(frame.tobytes())
                    except (BrokenPipeError, ValueError):
                        break
    except KeyboardInterrupt:
        pass
    finally:
        cap.release()
        if audio:
            audio.stop()
        if player and player.poll() is None:
            player.terminate()
    return 0


class _DummyAudio:
    def waveform(self, n: int = 400) -> np.ndarray:
        return np.zeros(n, dtype=np.int16)

    def level(self) -> float:
        return 0.0

    latest = None
    error = None


def run_bench(args: argparse.Namespace) -> int:
    base, token, debug_token = load_config()
    if not base or not token or not debug_token:
        print("Missing gateway config / FACE_DEBUG_TOKEN.", file=sys.stderr)
        return 2
    cap = cv2.VideoCapture(args.camera, cv2.CAP_V4L2)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
    if not cap.isOpened():
        print("camera not available", file=sys.stderr)
        return 2

    combos = [(320, 50), (320, 80), (480, 60), (480, 80), (720, 60), (720, 80)]
    print(f"{'dim':>5} {'q':>3} {'KB':>6} {'server_ms_p50':>13} {'rtt_p50':>8} {'rtt_p95':>8} {'fps':>6}")
    for dim, q in combos:
        server, rtt = [], []
        n = args.bench_frames
        t0 = time.time()
        for _ in range(n):
            ok, frame = cap.read()
            if not ok:
                break
            jpeg = encode_jpeg(frame, dim, q)
            res, r = post_face(base, token, debug_token, jpeg)
            rtt.append(r)
            if res.get("server_ms") is not None:
                server.append(res["server_ms"])
        dt = time.time() - t0
        if not rtt:
            continue
        print(f"{dim:>5} {q:>3} {len(jpeg) // 1024:>6} "
              f"{np.percentile(server, 50) if server else 0:>13.1f} "
              f"{np.percentile(rtt, 50):>8.1f} {np.percentile(rtt, 95):>8.1f} {n / dt:>6.2f}")
    cap.release()
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--camera", type=int, default=0)
    ap.add_argument("--width", type=int, default=640)
    ap.add_argument("--height", type=int, default=480)
    ap.add_argument("--max-dim", type=int, default=480, help="JPEG max dimension (app uses 480)")
    ap.add_argument("--quality", type=int, default=60, help="JPEG quality (app uses 60)")
    ap.add_argument("--interval-ms", type=int, default=600, help="min ms between uploads (app uses 600)")
    ap.add_argument("--no-audio", action="store_true")
    ap.add_argument("--print", action="store_true", help="also print HUD lines")
    ap.add_argument("--bench", action="store_true")
    ap.add_argument("--bench-frames", type=int, default=20)
    ap.add_argument("--frames", type=int, default=0, help="exit after N analyzed frames (0 = forever)")
    args = ap.parse_args()
    return run_bench(args) if args.bench else run_live(args)


if __name__ == "__main__":
    raise SystemExit(main())