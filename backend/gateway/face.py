from __future__ import annotations

import json
import logging
import threading

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

log = logging.getLogger("strokesense.gateway.face")

# MediaPipe FaceMesh landmark indices (match the Android AsymmetryCalculator).
EYE_L, EYE_R = 33, 263
MOUTH_L, MOUTH_R = 61, 291
CHEEK_L, CHEEK_R = 234, 454
BROW_L, BROW_R = 105, 334
LID_UP_L, LID_LO_L = 159, 145
LID_UP_R, LID_LO_R = 386, 374

KEYS = ["mouth_perp_abs", "eye_open_asym", "cheek_perp_abs", "brow_perp_abs"]


def feats(lm: np.ndarray) -> dict:
    """Corrected geometry (features_v2): eye-line axis, iod-normalized perp offsets."""
    mid = (lm[EYE_L] + lm[EYE_R]) / 2.0
    ev = lm[EYE_R] - lm[EYE_L]
    iod = float(np.hypot(*ev))
    if iod < 1e-4:
        return None
    u = ev / iod
    v = np.array([-u[1], u[0]])

    def perp(idx):
        return float(np.dot(lm[idx] - mid, v) / iod)

    open_l = float(np.hypot(*(lm[LID_UP_L] - lm[LID_LO_L])) / iod)
    open_r = float(np.hypot(*(lm[LID_UP_R] - lm[LID_LO_R])) / iod)
    eye_open_asym = abs(open_l - open_r) / (open_l + open_r) if open_l + open_r else 0.0
    return {
        "mouth_perp_abs": abs(perp(MOUTH_L) - perp(MOUTH_R)),
        "eye_open_asym": eye_open_asym,
        "cheek_perp_abs": abs(perp(CHEEK_L) - perp(CHEEK_R)),
        "brow_perp_abs": abs(perp(BROW_L) - perp(BROW_R)),
    }


class FaceServer:
    """Server-side face analysis: MediaPipe FaceLandmarker + the trained LR."""

    def __init__(self, model_path: str, lr_json_path: str) -> None:
        self._lock = threading.Lock()
        self._landmarker = vision.FaceLandmarker.create_from_options(
            vision.FaceLandmarkerOptions(
                base_options=mp_python.BaseOptions(model_asset_path=model_path),
                running_mode=vision.RunningMode.IMAGE,
                num_faces=1,
                min_face_detection_confidence=0.3,
                min_face_presence_confidence=0.3,
                min_tracking_confidence=0.3,
            )
        )
        params = json.load(open(lr_json_path))
        self._mean = np.array(params["mean"], dtype=np.float32)
        self._std = np.array(params["std"], dtype=np.float32)
        self._coef = np.array(params["coef"], dtype=np.float32)
        self._intercept = float(params["intercept"])
        self._threshold = float(params.get("threshold", 0.5535))
        log.info("face server ready (model=%s, lr threshold=%.4f)", model_path, self._threshold)

    def analyze(self, jpeg: bytes) -> dict:
        with self._lock:
            img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
            if img is None:
                return {"detected": False, "error": "bad image"}
            rgb = np.ascontiguousarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
            res = self._landmarker.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
            if not res.face_landmarks:
                return {"detected": False}
            lm = np.array([[p.x, p.y] for p in res.face_landmarks[0]])
            f = feats(lm)
            if f is None:
                return {"detected": False}
            z = (np.array([f[k] for k in KEYS]) - self._mean) / self._std
            logit = float(np.dot(self._coef, z)) + self._intercept
            score = 1.0 / (1.0 + np.exp(-logit))
            return {
                "detected": True,
                "score": round(score, 4),
                "mouth": round(f["mouth_perp_abs"], 5),
                "eye": round(f["eye_open_asym"], 5),
                "threshold": self._threshold,
            }