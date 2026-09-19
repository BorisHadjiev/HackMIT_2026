from __future__ import annotations

import hmac
import logging
import time
from collections import OrderedDict, deque
from contextlib import asynccontextmanager
from typing import Deque

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from .config import Settings, get_settings
from .linq import LinqClient, LinqError
from .models import AlertEnvelope, AlertResponse, HealthResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("strokesense.gateway")

IDEMPOTENCY_TTL_SECONDS = 24 * 3600
IDEMPOTENCY_MAX_ENTRIES = 10_000

_seen: "OrderedDict[str, tuple[float, AlertResponse]]" = OrderedDict()
_rate: dict[str, Deque[float]] = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    async with httpx.AsyncClient(timeout=15.0) as client:
        app.state.settings = settings
        app.state.linq = LinqClient(client, settings)
        log.info(
            "gateway ready: linq_configured=%s recipients=%d auth=%s",
            settings.linq_configured,
            len(settings.allowed_recipient_set),
            bool(settings.gateway_token),
        )
        yield


app = FastAPI(title="StrokeSense Alert Gateway", version="1.0.0", lifespan=lifespan)


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


def _idempotent_get(key: str) -> AlertResponse | None:
    entry = _seen.get(key)
    if entry is None:
        return None
    stored_at, response = entry
    if time.time() - stored_at > IDEMPOTENCY_TTL_SECONDS:
        _seen.pop(key, None)
        return None
    return response


def _idempotent_put(key: str, response: AlertResponse) -> None:
    _seen[key] = (time.time(), response)
    _seen.move_to_end(key)
    while len(_seen) > IDEMPOTENCY_MAX_ENTRIES:
        _seen.popitem(last=False)


def _compose_text(envelope: AlertEnvelope) -> str:
    heading = "URGENT" if envelope.severity == "urgent" else "StrokeSense update"
    return (
        f"{heading}: {envelope.title}\n\n"
        f"{envelope.body}\n\n"
        "Screening aid only, not a medical device. Reply STOP to opt out."
    )


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
    return HealthResponse(
        linq_configured=settings.linq_configured,
        recipients_configured=bool(settings.allowed_recipient_set),
    )


@app.post("/v1/stroke-alerts", response_model=AlertResponse)
async def stroke_alert(
    envelope: AlertEnvelope,
    request: Request,
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    x_alert_gateway_token: str | None = Header(default=None),
) -> AlertResponse:
    settings: Settings = request.app.state.settings
    _authorize(settings, x_alert_gateway_token)
    _check_rate(settings, x_alert_gateway_token or "anonymous")

    allowed = settings.allowed_recipient_set
    if not allowed:
        raise HTTPException(status_code=403, detail="no recipients configured on the gateway")
    if not settings.allow_any_recipient and envelope.recipient.phone not in allowed:
        raise HTTPException(status_code=403, detail="recipient not allowed")

    key = idempotency_key or envelope.alert_id
    cached = _idempotent_get(key)
    if cached is not None:
        return cached

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
        raise HTTPException(status_code=502, detail="Linq delivery failed")

    response = AlertResponse(trace_id=trace_id)
    _idempotent_put(key, response)
    return response
