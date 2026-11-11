"""F-257 / SF-257-02 — les sessions vocales ouvertes sur ce service (en mémoire, rien sur disque)."""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field

from .gateway import VoiceIdentity

#: Une trame audio au-delà est ignorée (et comptée) : 64 Kio ≈ 2 s de PCM 16 kHz mono 16 bits.
MAX_AUDIO_FRAME_BYTES = 64 * 1024


def server_ts() -> int:
    """Horodatage serveur en millisecondes (horloge murale) : le crochet de mesure du banc F-256."""
    return int(time.time() * 1000)


@dataclass
class VoiceSession:
    """Une conversation vocale : l'identité vérifiée, le ticket (mémoire seulement) et des compteurs sans contenu."""

    identity: VoiceIdentity
    ticket: str = field(repr=False)
    session_id: str = field(default_factory=lambda: uuid.uuid4().hex)
    opened_at_ms: int = field(default_factory=server_ts)
    audio_frames: int = 0
    audio_bytes: int = 0
    oversized_frames: int = 0

    def on_audio(self, data: bytes) -> bool:
        """Compte une trame audio ; ``False`` si elle est trop grande (ignorée)."""
        if len(data) > MAX_AUDIO_FRAME_BYTES:
            self.oversized_frames += 1
            return False
        self.audio_frames += 1
        self.audio_bytes += len(data)
        return True


class SessionRegistry:
    """Le plafond de sessions simultanées du service (protège la mémoire d'un cluster sans marge)."""

    def __init__(self, max_sessions: int):
        self._max = max_sessions
        self._sessions: dict[str, VoiceSession] = {}

    def __len__(self) -> int:
        return len(self._sessions)

    def full(self) -> bool:
        return len(self._sessions) >= self._max

    def add(self, session: VoiceSession) -> None:
        self._sessions[session.session_id] = session

    def remove(self, session: VoiceSession) -> None:
        self._sessions.pop(session.session_id, None)
