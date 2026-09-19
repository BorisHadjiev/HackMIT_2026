from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Gateway configuration, loaded from environment / .env.

    The Linq integration token is intentionally server-side only. It must never
    be shipped in the Android APK.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    linq_api_token: str = ""
    linq_from_number: str = ""
    linq_base_url: str = "https://api.linqapp.com"

    # Shared secret the app sends as X-Alert-Gateway-Token. Empty disables the check.
    gateway_token: str = ""

    # Comma-separated E.164 allowlist. Empty rejects every recipient.
    allowed_recipients: str = ""

    bind: str = "127.0.0.1:8000"
    rate_limit_per_minute: int = 30
    max_body_bytes: int = 16384

    @property
    def allowed_recipient_set(self) -> set[str]:
        return {part.strip() for part in self.allowed_recipients.split(",") if part.strip()}

    @property
    def allow_any_recipient(self) -> bool:
        """`ALLOWED_RECIPIENTS=*` lets each app user pick their own trusted contact."""
        return "*" in self.allowed_recipient_set

    @property
    def linq_configured(self) -> bool:
        return bool(self.linq_api_token and self.linq_from_number)


@lru_cache
def get_settings() -> Settings:
    return Settings()
