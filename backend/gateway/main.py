from __future__ import annotations

import asyncio
import hmac
import json
import logging
import time
from collections import deque
from contextlib import asynccontextmanager
from typing import Deque
from urllib.parse import urlencode

import httpx
import numpy as np
from fastapi import FastAPI, Header, HTTPException, Request, WebSocket
from fastapi.responses import JSONResponse, Response
from websockets.asyncio.client import connect as ws_connect
from websockets.exceptions import ConnectionClosed

from .agent import AgentClient
from .config import Settings, get_settings
from .database import Database
from .linq import LinqClient, LinqError
from .models import (
    AgentRequest,
    AgentResponse,
    AlertEnvelope,
    AlertResponse,
    HealthResponse,
    TtsRequest,
)
from .speaker import SpeakerGate, speech_segments
from .tts import TtsEngine, TtsError, wav_bytes

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("strokesense.gateway")

_rate: dict[str, Deque[float]] = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    db = Database(settings.db_path)
    await db.connect()
    async with httpx.AsyncClient(timeout=15.0) as client:
        app.state.settings = settings
        app.state.db = db
        app.state.linq = LinqClient(client, settings)
        app.state.tts = TtsEngine(settings)
        app.state.agent = AgentClient(settings)
        app.state.speaker = SpeakerGate(settings)
        log.info(
            "gateway ready: linq=%s deepgram=%s recipients=%d auth=%s db=%s",
            settings.linq_configured,
            settings.deepgram_configured,
            len(settings.allowed_recipient_set),
            bool(settings.gateway_token),
            settings.db_path,
        )
        yield
    await db.close()


app = FastAPI(title="StrokeSense Alert Gateway", version="1.1.0", lifespan=lifespan)


def _authorize(settings: Settings, provided: str | None) -> None:
    if not settings.gateway_token:
        return
    if not provided or not hmac.compare_digest(provided, settings.gateway_token):
        raise HTTPException(status_code=401, detail="invalid gateway token")


def _check_rate(settings: Settings, key: str) -> None:
    now = time.time()
    bucket = _rate.setdefault(key, deque())
    while bucket and now - bucket[0] > 60:
        bucket.popleft()
    if len(bucket) >= settings.rate_limit_per_minute:
        raise HTTPException(status_code=429, detail="rate limit exceeded")
    bucket.append(now)


def _compose_text(envelope: AlertEnvelope) -> str:
    heading = "URGENT" if envelope.severity == "urgent" else "StrokeSense update"
    return (
        f"{heading}: {envelope.title}\n\n"
        f"{envelope.body}\n\n"
        "Screening aid only, not a medical device. Reply STOP to opt out."
    )


def _split_trace(trace_id: str | None) -> tuple[str | None, str | None]:
    if not trace_id or not trace_id.startswith("linq:"):
        return None, None
    parts = trace_id.split(":")
    if len(parts) == 3:
        return parts[1], parts[2]
    return None, None


def _to_ws_url(url: str) -> str:
    """Deepgram base URLs are https://; websockets requires ws(s)://."""
    return url.replace("https://", "wss://").replace("http://", "ws://")


@app.middleware("http")
async def limit_body_size(request: Request, call_next):
    settings = getattr(request.app.state, "settings", None)
    content_length = request.headers.get("content-length")
    if settings is not None and content_length and int(content_length) > settings.max_body_bytes:
        return JSONResponse(status_code=413, content={"detail": "payload too large"})
    return await call_next(request)


@app.get("/healthz", response_model=HealthResponse)
async def healthz(request: Request) -> HealthResponse:
    settings: Settings = request.app.state.settings
    db: Database = request.app.state.db
    db_ok = await db.ping()
    return HealthResponse(
        status="ok" if db_ok else "degraded",
        linq_configured=settings.linq_configured,
        deepgram_configured=settings.deepgram_configured,
        recipients_configured=bool(settings.allowed_recipient_set),
        alerts_recorded=await db.count_rows("alerts"),
        webhook_events=await db.count_rows("webhook_events"),
    )


@app.post("/v1/stroke-alerts", response_model=AlertResponse)
async def stroke_alert(
    envelope: AlertEnvelope,
    request: Request,
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    x_alert_gateway_token: str | None = Header(default=None),
) -> AlertResponse:
    settings: Settings = request.app.state.settings
    db: Database = request.app.state.db
    _authorize(settings, x_alert_gateway_token)
    _check_rate(settings, x_alert_gateway_token or "anonymous")

    allowed = settings.allowed_recipient_set
    if not allowed:
        raise HTTPException(status_code=403, detail="no recipients configured on the gateway")
    if not settings.allow_any_recipient and envelope.recipient.phone not in allowed:
        raise HTTPException(status_code=403, detail="recipient not allowed")

    key = idempotency_key or envelope.alert_id
    cached = await db.idem_get(key)
    if cached is not None:
        return AlertResponse.model_validate_json(cached["response_json"])

    if not settings.linq_configured:
        raise HTTPException(
            status_code=503,
            detail="Linq is not configured on the gateway (LINQ_API_TOKEN / LINQ_FROM_NUMBER).",
        )

    try:
        trace_id = await request.app.state.linq.send(
            _compose_text(envelope), envelope.recipient.phone, key
        )
    except LinqError:
        await db.record_alert(
            {
                "alert_id": envelope.alert_id,
                "created_at_ms": envelope.created_at_ms or int(time.time() * 1000),
                "source": envelope.source,
                "kind": envelope.kind,
                "severity": envelope.severity,
                "title": envelope.title,
                "body": envelope.body,
                "recipient_name": envelope.recipient.name,
                "recipient_phone": envelope.recipient.phone,
                "status": "failed",
            }
        )
        raise HTTPException(status_code=502, detail="Linq delivery failed")

    response = AlertResponse(trace_id=trace_id)
    chat_id, message_id = _split_trace(trace_id)
    inserted = await db.idem_put(key, response.model_dump_json())
    if not inserted:
        cached = await db.idem_get(key)
        if cached is not None:
            return AlertResponse.model_validate_json(cached["response_json"])

    await db.record_alert(
        {
            "alert_id": envelope.alert_id,
            "created_at_ms": envelope.created_at_ms or int(time.time() * 1000),
            "source": envelope.source,
            "kind": envelope.kind,
            "severity": envelope.severity,
            "title": envelope.title,
            "body": envelope.body,
            "recipient_name": envelope.recipient.name,
            "recipient_phone": envelope.recipient.phone,
            "status": "sent",
            "trace_id": trace_id,
            "linq_chat_id": chat_id,
            "linq_message_id": message_id,
            "response_json": response.model_dump_json(),
            "sent_at_ms": int(time.time() * 1000),
        }
    )
    return response


@app.post("/v1/webhooks/linq")
async def linq_webhook(request: Request) -> dict:
    settings: Settings = request.app.state.settings
    db: Database = request.app.state.db
    body = await request.json()

    if settings.webhook_secret:
        signature = request.headers.get("X-Linq-Signature", "")
        if not signature or not hmac.compare_digest(signature, settings.webhook_secret):
            raise HTTPException(status_code=401, detail="invalid webhook signature")

    data = body.get("data") or {}
    await db.record_webhook_event(
        {
            "event_id": body.get("event_id"),
            "event_type": body.get("event_type"),
            "linq_chat_id": data.get("chat_id"),
            "linq_message_id": data.get("id"),
            "delivery_status": data.get("delivery_status"),
            "payload_json": json.dumps(body),
        }
    )
    log.info("linq webhook %s chat=%s msg=%s", body.get("event_type"), data.get("chat_id"), data.get("id"))
    return {"status": "ok"}


@app.post("/v1/linq/webhook-subscription")
async def register_linq_webhook(
    request: Request,
    x_alert_gateway_token: str | None = Header(default=None),
) -> dict:
    settings: Settings = request.app.state.settings
    _authorize(settings, x_alert_gateway_token)
    if not settings.linq_public_base_url:
        raise HTTPException(status_code=400, detail="LINQ_PUBLIC_BASE_URL not configured")
    if not settings.linq_configured:
        raise HTTPException(status_code=503, detail="Linq is not configured on the gateway")

    webhook_url = f"{settings.linq_public_base_url.rstrip('/')}/v1/webhooks/linq"
    try:
        linq_response = await request.app.state.linq.register_webhook(webhook_url)
    except LinqError as exc:
        raise HTTPException(status_code=502, detail=str(exc))
    return {"webhook_url": webhook_url, "linq_response": linq_response}


@app.post("/v1/tts")
async def synthesize_speech(
    req: TtsRequest,
    request: Request,
    x_alert_gateway_token: str | None = Header(default=None),
) -> Response:
    settings: Settings = request.app.state.settings
    _authorize(settings, x_alert_gateway_token)
    try:
        samples, rate = request.app.state.tts.synthesize(req.text, req.voice)
    except TtsError as exc:
        raise HTTPException(status_code=503, detail=str(exc))
    return Response(
        content=wav_bytes(samples, rate),
        media_type="audio/wav",
        headers={"Cache-Control": "max-age=3600"},
    )


@app.post("/v1/agent", response_model=AgentResponse)
async def voice_agent(
    req: AgentRequest,
    request: Request,
    x_alert_gateway_token: str | None = Header(default=None),
) -> AgentResponse:
    settings: Settings = request.app.state.settings
    _authorize(settings, x_alert_gateway_token)
    result = await request.app.state.agent.answer(req.question, req.task_context, req.history)
    return AgentResponse(**result)


def _wav_to_pcm(body: bytes) -> np.ndarray:
    """Accept RIFF WAV or raw 16 kHz PCM16; return float32 PCM."""
    if body.startswith(b"RIFF"):
        i = 12
        while i + 8 <= len(body):
            cid = body[i : i + 4]
            size = int.from_bytes(body[i + 4 : i + 8], "little")
            if cid == b"data":
                pcm = np.frombuffer(body[i + 8 : i + 8 + size], dtype=np.int16)
                return pcm.astype(np.float32) / 32768.0
            i += 8 + size + (size & 1)
    pcm = np.frombuffer(body, dtype=np.int16)
    return pcm.astype(np.float32) / 32768.0


@app.post("/v1/speaker/enroll")
async def enroll_speaker(request: Request, x_alert_gateway_token: str | None = Header(default=None)) -> dict:
    settings: Settings = request.app.state.settings
    _authorize(settings, x_alert_gateway_token)
    body = await request.body()
    if len(body) < 16000:
        raise HTTPException(status_code=400, detail="need at least 1s of 16 kHz PCM")
    pcm = _wav_to_pcm(body)
    dims = await asyncio.to_thread(request.app.state.speaker.enroll, pcm, 16000)
    log.info("enrolled speaker embedding (%d dims)", dims)
    return {"status": "enrolled", "dims": dims, "gating": settings.speaker_gate_enabled}


@app.get("/v1/speaker/status")
async def speaker_status(request: Request, x_alert_gateway_token: str | None = Header(default=None)) -> dict:
    settings: Settings = request.app.state.settings
    _authorize(settings, x_alert_gateway_token)
    sp = request.app.state.speaker
    return {
        "gating_enabled": settings.speaker_gate_enabled,
        "enrolled": sp._embedding is not None,
        "threshold": settings.speaker_threshold,
    }


@app.get("/v1/linq/status")
async def linq_status(request: Request, x_alert_gateway_token: str | None = Header(default=None)) -> dict:
    settings: Settings = request.app.state.settings
    _authorize(settings, x_alert_gateway_token)
    try:
        numbers = await request.app.state.linq.phone_numbers()
    except LinqError as exc:
        raise HTTPException(status_code=502, detail=str(exc))
    phone_list = [n.get("phone_number") for n in numbers.get("phone_numbers", [])]
    return {
        "configured": settings.linq_configured,
        "send_from": settings.linq_from_number,
        "provisioned": phone_list,
        "send_from_ok": settings.linq_from_number in phone_list,
    }


@app.websocket("/v1/deepgram/stream")
async def deepgram_proxy(websocket: WebSocket) -> None:
    settings: Settings = websocket.app.state.settings
    token = websocket.headers.get("Authorization")
    if token and token.lower().startswith("token "):
        token = token[6:].strip()
    if token is None:
        token = websocket.query_params.get("token")

    if settings.gateway_token and (not token or not hmac.compare_digest(token, settings.gateway_token)):
        await websocket.close(code=4401, reason="unauthorized")
        return
    if not settings.deepgram_configured:
        await websocket.close(code=4403, reason="deepgram not configured")
        return

    await websocket.accept()
    params = {k: v for k, v in websocket.query_params.items() if k.lower() != "token"}
    dg_url = f"{_to_ws_url(settings.deepgram_base_url)}?{urlencode(params)}"
    speaker: SpeakerGate = websocket.app.state.speaker

    try:
        async with ws_connect(
            dg_url,
            additional_headers={"Authorization": f"Token {settings.deepgram_api_key}"},
            open_timeout=15,
        ) as dg:

            async def client_to_dg() -> None:
                gate_buf = bytearray() if speaker.enabled else None
                try:
                    while True:
                        msg = await websocket.receive()
                        if msg.get("type") == "websocket.disconnect":
                            try:
                                await dg.close()
                            except Exception:
                                pass
                            return
                        text = msg.get("text")
                        if text is not None:
                            await dg.send(text)
                        data = msg.get("bytes")
                        if data is not None:
                            if gate_buf is None:
                                await dg.send(data)
                            else:
                                gate_buf += data
                                if len(gate_buf) < 16000:
                                    continue
                                buf = bytes(gate_buf)
                                samples = np.frombuffer(buf, dtype=np.int16).astype(np.float32) / 32768.0
                                segs = speech_segments(samples, 16000)
                                last_end = 0
                                for start, end in segs:
                                    emb = await asyncio.to_thread(speaker.embed_wav, samples[start:end])
                                    ok, sim = speaker.is_user(emb)
                                    if ok:
                                        await dg.send(buf[start * 2 : end * 2])
                                    else:
                                        log.info("speaker gate: dropped segment sim=%.2f", sim)
                                    last_end = end
                                if last_end:
                                    gate_buf[:] = buf[last_end * 2 :]
                                elif len(gate_buf) > 32000:
                                    gate_buf[:] = buf[-32000:]  # keep last ~2s tail
                except Exception:
                    return

            async def dg_to_client() -> None:
                try:
                    async for msg in dg:
                        if isinstance(msg, str):
                            await websocket.send_text(msg)
                        else:
                            await websocket.send_bytes(msg)
                except Exception:
                    return

            client_task = asyncio.create_task(client_to_dg())
            upstream_task = asyncio.create_task(dg_to_client())
            done, pending = await asyncio.wait(
                {client_task, upstream_task}, return_when=asyncio.FIRST_COMPLETED
            )
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)
    except ConnectionClosed:
        pass
    except Exception as exc:
        log.warning("deepgram proxy ended: %s", exc)
    finally:
        try:
            await websocket.close()
        except Exception:
            pass