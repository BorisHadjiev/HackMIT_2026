"""Golden parity tests for the face + slur analyzers.

These guard against silent drift in the geometry/model that the app relies on.
They need the ML extras (mediapipe / torch), so they SKIP when those aren't
installed — CI without requirements-ml stays green, while gx10 runs them fully.

The face fixture is public-domain (scikit-image's NASA 'astronaut', via the
gateway's own analyze path); the audio fixture is the app's bundled healthy clip.
"""
from __future__ import annotations

import json
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")
GOLDEN = json.load(open(os.path.join(FIX, "golden.json")))


def _face_model_path() -> str | None:
    for cand in (
        os.environ.get("FACE_MODEL_PATH"),
        "models/face/face_landmarker.task",
        os.path.join(os.path.expanduser("~"), "strokesense-gateway", "models", "face", "face_landmarker.task"),
    ):
        if cand and os.path.exists(cand):
            return cand
    return None


def _face_lr_path() -> str | None:
    for cand in (
        os.environ.get("FACE_LR_PATH"),
        "models/face/lr_model.json",
        os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "..", "tools", "face_eval", "lr_model.json"),
    ):
        if cand and os.path.exists(cand):
            return cand
    return None


@pytest.mark.skipif(_face_model_path() is None, reason="face_landmarker.task not available")
def test_face_debug_parity():
    pytest.importorskip("mediapipe")
    pytest.importorskip("cv2")
    from gateway.face import FaceServer

    server = FaceServer(_face_model_path(), _face_lr_path())
    jpeg = open(os.path.join(FIX, "face_public.jpg"), "rb").read()

    prod = server.analyze(jpeg)
    assert prod["detected"] is True
    for key in ("score", "score_cal", "quality", "mouth", "eye", "threshold"):
        assert key in prod

    dbg = server.analyze_debug(jpeg)
    assert dbg["detected"] is True
    assert dbg["landmark_count"] == 478
    assert len(dbg["landmarks"]) == 478
    assert set(dbg["feats"]) == {"mouth_perp_abs", "eye_open_asym", "cheek_perp_abs", "brow_perp_abs"}
    assert len(dbg["z"]) == 4

    g = GOLDEN["face"]
    assert abs(dbg["score"] - g["score"]) < 0.05, (dbg["score"], g["score"])
    assert abs(dbg["score_cal"] - g["score_cal"]) < 0.05
    for k, v in g["feats"].items():
        assert abs(dbg["feats"][k] - v) < 5e-3, (k, dbg["feats"][k], v)


@pytest.mark.skipif(_face_model_path() is None, reason="face model not available")
def test_face_debug_disabled_returns_404_when_gate_off():
    """The debug route must 404 unless FACE_DEBUG_ENABLED is set."""
    import asyncio

    import httpx

    pytest.importorskip("mediapipe")
    pytest.importorskip("cv2")
    from gateway.config import Settings
    from gateway import main

    main.app.state.settings = Settings(face_debug_enabled=False, gateway_token="")

    async def run() -> int:
        transport = httpx.ASGITransport(app=main.app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post("/v1/face/debug", content=b"x")
            return resp.status_code

    assert asyncio.run(run()) == 404


def _slur_model_path() -> str | None:
    for cand in (
        os.environ.get("SLUR_MODEL_PATH"),
        "models/slur/slur_classifier_ssl_pathological.json",
        os.path.join(os.path.expanduser("~"), "strokesense-gateway", "models", "slur", "slur_classifier_ssl_pathological.json"),
    ):
        if cand and os.path.exists(cand):
            return cand
    return None


@pytest.mark.skipif(_slur_model_path() is None, reason="slur model not available")
def test_slur_analyze_returns_probability():
    pytest.importorskip("torch")
    pytest.importorskip("transformers")
    from gateway.slur import SlurServer

    # No profile path -> corpus mode (deterministic-ish, no per-user state).
    server = SlurServer(_slur_model_path(), profile_path="")
    wav = open(os.path.join(FIX, "healthy.wav"), "rb").read()
    out = server.analyze(wav)
    assert 0.0 <= out["score"] <= 1.0
    assert out["mode"] == "corpus"
    assert isinstance(out["detected"], bool)