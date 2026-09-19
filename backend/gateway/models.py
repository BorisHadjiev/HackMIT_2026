from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

AlertSource = Literal["assessment", "deepgram", "elastic", "system"]
AlertKind = Literal[
    "no_test_detected",
    "assessment_summary",
    "emergency_recommended",
    "external_signal",
]
AlertSeverity = Literal["informational", "urgent"]


class Recipient(BaseModel):
    name: str = ""
    phone: str = Field(min_length=7, max_length=20)


class AlertEnvelope(BaseModel):
    """Matches LinqAlertGateway.kt on the Android client."""

    alert_id: str = Field(min_length=1, max_length=128)
    source: AlertSource
    kind: AlertKind
    severity: AlertSeverity
    title: str = Field(min_length=1, max_length=200)
    body: str = Field(min_length=1, max_length=4000)
    created_at_ms: int | None = None
    recipient: Recipient


class AlertResponse(BaseModel):
    status: str = "sent"
    trace_id: str


class HealthResponse(BaseModel):
    status: str = "ok"
    linq_configured: bool
    recipients_configured: bool
