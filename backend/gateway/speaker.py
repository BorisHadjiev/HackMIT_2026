from __future__ import annotations

import logging
import os

import numpy as np

from .config import Settings

log = logging.getLogger("strokesense.gateway.speaker")


class SpeakerGate:
    """Server-side speaker verification for the Deepgram proxy.

    Enrolls the user's voice (from the calibration audio) and only forwards
    speech that matches the enrolled speaker to Deepgram, so other voices and
    background audio never leave the LAN.
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._encoder = None
        self._embedding: np.ndarray | None = None
        self._load_embedding()

    @property
    def enabled(self) -> bool:
        return self._settings.speaker_gate_enabled and self._embedding is not None

    def _ensure_encoder(self):
        if self._encoder is None:
            try:
                import torch
                from speechbrain.pretrained import EncoderClassifier

                self._torch = torch
                self._encoder = EncoderClassifier.from_hparams(
                    source="speechbrain/spkrec-ecapa-voxceleb",
                    savedir=os.path.join(self._settings.tts_models_dir, "ecapa"),
                )
            except Exception as exc:
                log.warning("speechbrain ECAPA unavailable: %s", exc)
                raise RuntimeError(f"speaker encoder unavailable: {exc}")

    def _load_embedding(self) -> None:
        path = self._settings.speaker_embedding_path
        if os.path.exists(path):
            try:
                self._embedding = np.load(path)
                log.info("loaded speaker embedding (%d dims)", self._embedding.shape[0])
            except Exception as exc:
                log.warning("could not load speaker embedding: %s", exc)

    def embed_wav(self, pcm: np.ndarray, sr: int = 16000) -> np.ndarray:
        self._ensure_encoder()
        import torchaudio

        wav = self._torch.from_numpy(np.asarray(pcm, dtype=np.float32)).unsqueeze(0)
        if sr != 16000:
            wav = torchaudio.functional.resample(wav, sr, 16000)
        with self._torch.no_grad():
            emb = self._encoder.encode_batch(wav).squeeze()
        return emb.detach().cpu().numpy().astype(np.float32)

    def enroll(self, pcm: np.ndarray, sr: int = 16000) -> int:
        emb = self.embed_wav(pcm, sr)
        path = self._settings.speaker_embedding_path
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        np.save(path, emb)
        self._embedding = emb
        return int(emb.shape[0])

    def is_user(self, emb: np.ndarray, threshold: float | None = None) -> tuple[bool, float]:
        if self._embedding is None:
            return False, 0.0
        thr = threshold if threshold is not None else self._settings.speaker_threshold
        sim = float(np.dot(emb, self._embedding) / (np.linalg.norm(emb) * np.linalg.norm(self._embedding) + 1e-9))
        return sim >= thr, sim


# Lightweight energy VAD for gating windows.
def speech_segments(
    samples: np.ndarray,
    sr: int = 16000,
    frame_ms: int = 30,
    threshold: float = 0.008,
    min_speech_s: float = 0.5,
    min_silence_s: float = 0.4,
) -> list[tuple[int, int]]:
    frame = int(sr * frame_ms / 1000)
    if samples.size < frame:
        return []
    n = samples.size // frame
    frames = samples[: n * frame].reshape(n, frame)
    rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))
    speech = rms > threshold
    min_speech = int(min_speech_s * 1000 / frame_ms)
    min_silence = int(min_silence_s * 1000 / frame_ms)

    segments: list[tuple[int, int]] = []
    start = None
    silence_run = 0
    for i, is_sp in enumerate(speech):
        if is_sp and start is None:
            start = i
            silence_run = 0
        elif is_sp and start is not None:
            silence_run = 0
        elif not is_sp and start is not None:
            silence_run += 1
            if silence_run >= min_silence:
                if i - start >= min_speech:
                    segments.append((start * frame, i * frame))
                start = None
                silence_run = 0
    if start is not None and len(samples) - start * frame >= min_speech * sr:
        segments.append((start * frame, len(samples)))
    return segments