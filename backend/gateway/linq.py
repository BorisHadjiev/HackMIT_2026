from __future__ import annotations

import logging

import httpx

from .config import Settings

log = logging.getLogger("strokesense.gateway.linq")


class LinqError(Exception):
    """Raised when the Linq Partner API rejects or fails a request."""


class LinqClient:
    """Thin wrapper around the Linq Partner API v2 chat endpoint.

    Sending to a phone number creates (or reuses) a 1:1 chat and posts the
    message in a single call.
    """

    def __init__(self, client: httpx.AsyncClient, settings: Settings) -> None:
        self._client = client
        self._settings = settings

    async def send(self, text: str, phone: str, idempotency_key: str) -> str:
        payload = {
            "send_from": self._settings.linq_from_number,
            "chat": {"phone_numbers": [phone]},
            "message": {"text": text, "idempotency_key": idempotency_key},
        }
        try:
            response = await self._client.post(
                f"{self._settings.linq_base_url}/api/partner/v2/chats",
                headers={
                    "X-LINQ-INTEGRATION-TOKEN": self._settings.linq_api_token,
                    "Content-Type": "application/json",
                },
                json=payload,
            )
        except httpx.HTTPError as exc:
            raise LinqError(f"transport error: {exc}") from exc

        if response.status_code not in (200, 201):
            # Do not log the payload; it may contain the recipient phone number.
            log.warning("linq send failed: status=%s body=%s", response.status_code, response.text[:200])
            raise LinqError(f"linq returned {response.status_code}")

        data = response.json().get("data", {}) or {}
        chat_id = data.get("id")
        message = data.get("chat_messages") or {}
        message_id = message.get("id")
        return f"linq:{chat_id}:{message_id}"

    async def register_webhook(self, webhook_url: str) -> dict:
        """Creates a Linq webhook subscription pointing at our receiver."""
        payload = {
            "webhook_subscription": {
                "webhook_url": webhook_url,
                "events": ["message.received", "message.sent"],
                "version": 2,
                "active": True,
            }
        }
        response = await self._client.post(
            f"{self._settings.linq_base_url}/api/partner/v2/webhook_subscriptions",
            headers={
                "X-LINQ-INTEGRATION-TOKEN": self._settings.linq_api_token,
                "Content-Type": "application/json",
            },
            json=payload,
        )
        if response.status_code not in (200, 201):
            raise LinqError(
                f"linq webhook registration returned {response.status_code}: {response.text[:200]}"
            )
        return response.json()

    async def phone_numbers(self) -> dict:
        """Lists the phone numbers provisioned to the integration token's org."""
        response = await self._client.get(
            f"{self._settings.linq_base_url}/api/partner/v2/phone_numbers",
            headers={"X-LINQ-INTEGRATION-TOKEN": self._settings.linq_api_token},
        )
        if response.status_code != 200:
            raise LinqError(f"linq phone_numbers returned {response.status_code}: {response.text[:200]}")
        return response.json()
