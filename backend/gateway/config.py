from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Gateway configuration, loaded from environment / .env.

    The Linq integration token and Deepgram API key are intentionally
    server-side only. They must never ship in the Android APK.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    linq_api_token: str = ""
    linq_from_number: str = ""
    linq_base_url: str = "https://api.linqapp.com"

    # Deepgram: the key stays here; the APK talks to /v1/deepgram/stream instead.
    deepgram_api_key: str = ""
    deepgram_base_url: str = "https://api.deepgram.com/v1/listen"

    # Public base used when registering the Linq webhook (e.g. https://work.tail043976.ts.net)
    linq_public_base_url: str = ""

    # Shared secret the app sends as X-Alert-Gateway-Token (and as the Deepgram
    # proxy Authorization token). Empty disables the checks.
    gateway_token: str = ""

    # Comma-separated E.164 allowlist. "*" allows any recipient the app sends.
    # Empty rejects every recipient.
    allowed_recipients: str = ""

    # SQLite file for idempotency + alert audit + webhook events.
    db_path: str = "strokesense.db"

    # Optional shared secret for verifying Linq webhook callbacks.
    webhook_secret: str = ""

    # TTS (local, on gx10). Engine: kokoro (fallback piper).
    tts_engine: str = "kokoro"
    tts_voice: str = "af_heart"
    tts_models_dir: str = "models/tts"

    # Local LLM (Ollama) for the voice agent.
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_model: str = "qwen3.8:latest"

    # Speaker gating for the Deepgram proxy.
    speaker_gate_enabled: bool = False
    speaker_threshold: float = 0.75
    speaker_embedding_path: str = "speaker_embedding.npy"

    # Server-side face analysis (MediaPipe + trained LR).
    face_model_path: str = "models/face/face_landmarker.task"
    face_lr_path: str = "models/face/lr_model.json"

    # Server-side slur classification (WavLM embedding + SSL-only LR).
    slur_model_path: str = "models/slur/slur_classifier_ssl_pathological.json"
    # Per-user slur voice profile (personal-centroid mode). Single-user demo file.
    slur_profile_path: str = "models/slur/slur_profile.json"

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

    @property
    def deepgram_configured(self) -> bool:
        return bool(self.deepgram_api_key)


@lru_cache
def get_settings() -> Settings:
    return Settings()