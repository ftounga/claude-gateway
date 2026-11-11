"""F-257 / SF-257-04 — **une session de transcription longue** par conversation (ADR-021, OQ-24 décision 3).

Le service ouvre UNE session chez le fournisseur (ElevenLabs Scribe v2 Realtime, *commit* manuel) par un jeton
éphémère obtenu de la passerelle au nom de l'utilisateur, y pousse le micro en continu par blocs d'≈ 100 ms, et la
**rouvre seul** quand le fournisseur la coupe (≈ 24,5 min mesurées, `queue_overflow`, réseau), en rejouant l'audio
envoyé depuis le dernier texte final (≤ 2 s) : aucune phrase n'est perdue à la reprise.

Ce que le fournisseur fait, vérifié le 2026-11-11 : en *commit* manuel il émet quand même un `committed_transcript`
(vide au silence) toutes les ≈ 36 s — accepté sans être pris pour une fin de tour ; chaque fermeture est précédée
d'un message (`auth_error`, `invalid_request`, `rate_limited`, `queue_overflow`…) dont seul le **type** est gardé.

Rien de ce qui est dit n'est journalisé : seulement des codes, des types et des durées.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import time
from collections import deque
from dataclasses import dataclass
from typing import Awaitable, Callable, Protocol
from urllib.parse import quote

from .gateway import GatewayClient, GatewayUnavailable, SttTicket, TicketRejected

log = logging.getLogger("jarvis-voice.stt")

SAMPLE_RATE = 16_000
#: ≈ 100 ms de PCM 16 bits mono à 16 kHz : jamais de rafale de petits blocs (cause de `queue_overflow`).
CHUNK_BYTES = 3_200
#: L'audio envoyé et pas encore confirmé par un texte final, rejoué à la reprise (borné à 2 s).
RING_BYTES = 2 * SAMPLE_RATE * 2
#: Rejeu à la reprise : un bloc toutes les 25 ms (4× le temps réel), pour ne pas déborder la file du fournisseur.
REPLAY_GAP_S = 0.025
#: Au-delà de tant d'échecs d'ouverture d'affilée, la session s'arrête (l'écran retombe sur l'ancienne boucle).
MAX_CONSECUTIVE_FAILURES = 5
#: Le fournisseur doit accepter la session dans ce délai.
START_TIMEOUT_S = 5.0
#: File d'envoi bornée (≈ 6 s d'audio) : au-delà, le plus ancien bloc non envoyé est jeté (et compté).
QUEUE_MAX = 60


class SttEvents(Protocol):
    """Ce que la session longue rapporte (au WebSocket de l'écran, aux traces)."""

    async def partial(self, text: str) -> None: ...

    async def final(self, text: str) -> None: ...

    async def state(self, state: str, detail: str) -> None: ...


@dataclass
class SttCounters:
    """Compteurs sans contenu de la conversation."""

    sessions: int = 0
    reopens: int = 0
    finals: int = 0
    empty_finals: int = 0
    dropped_chunks: int = 0
    replayed_bytes: int = 0


Connect = Callable[[str], Awaitable[object]]


async def _default_connect(url: str):
    from websockets.asyncio.client import connect
    return await connect(url, open_timeout=START_TIMEOUT_S, max_size=2 ** 20)


def provider_url(ticket: SttTicket) -> str:
    """L'URL de session avec son jeton (jamais journalisée)."""
    return f"{ticket.url}{'&' if '?' in ticket.url else '?'}token={quote(ticket.token, safe='')}"


def classify(message: str) -> tuple[str, str]:
    """Le type d'un message du fournisseur et son texte : ``partial``, ``final``, ``started``, ``error:<type>``,
    ``other``. Le texte d'une erreur n'est jamais rendu (il peut citer ce qui a été dit)."""
    try:
        data = json.loads(message)
    except ValueError:
        return "other", ""
    if not isinstance(data, dict):
        return "other", ""
    kind = data.get("message_type") if isinstance(data.get("message_type"), str) else ""
    text = data.get("text") if isinstance(data.get("text"), str) else ""
    if kind == "session_started":
        return "started", ""
    if kind == "partial_transcript":
        return "partial", text
    if kind.startswith("committed_transcript"):
        return "final", text
    if "error" in kind or data.get("error") or kind in ("rate_limited", "queue_overflow", "quota_exceeded",
                                                         "session_time_limit_exceeded", "invalid_request"):
        safe = "".join(c for c in kind if c.isalnum() or c == "_")[:40] or "inconnu"
        return f"error:{safe}", ""
    return "other", ""


def audio_message(pcm: bytes, commit: bool = False) -> str:
    return json.dumps({"message_type": "input_audio_chunk", "audio_base_64": base64.b64encode(pcm).decode("ascii"),
                       "commit": commit, "sample_rate": SAMPLE_RATE})


class LongTranscription:
    """La session d'écoute longue d'une conversation."""

    def __init__(self, gateway: GatewayClient, ticket: str, events: SttEvents, connect: Connect | None = None,
                 clock: Callable[[], float] = time.monotonic, replay_gap_s: float = REPLAY_GAP_S,
                 retry_delay_s: float = 0.25):
        self._gateway = gateway
        self._ticket = ticket
        self._events = events
        self._connect = connect or _default_connect
        self._clock = clock
        self._replay_gap_s = replay_gap_s
        self._retry_delay_s = retry_delay_s
        self._pending = bytearray()
        self._queue: asyncio.Queue[bytes] = asyncio.Queue()
        self._ring: deque[bytes] = deque()
        self._ring_bytes = 0
        self._commit_requested = asyncio.Event()
        self._closing = False
        self._runner: asyncio.Task | None = None
        self.counters = SttCounters()
        self.last_close: str | None = None

    # ---- côté conversation -------------------------------------------------------------------------------------

    def start(self) -> None:
        if self._runner is None:
            self._runner = asyncio.create_task(self._run())

    def push(self, pcm: bytes) -> None:
        """Un bloc du micro (PCM 16 bits mono 16 kHz), regroupé par ≈ 100 ms."""
        if self._closing:
            return
        self._pending.extend(pcm)
        while len(self._pending) >= CHUNK_BYTES:
            self._enqueue(bytes(self._pending[:CHUNK_BYTES]))
            del self._pending[:CHUNK_BYTES]

    def commit(self) -> None:
        """Demande au fournisseur le texte final de ce qui a été envoyé (fin de tour décidée par le service)."""
        if self._pending:
            self._enqueue(bytes(self._pending))
            self._pending.clear()
        self._commit_requested.set()

    async def close(self) -> None:
        self._closing = True
        if self._runner:
            self._runner.cancel()
            try:
                await self._runner
            except (asyncio.CancelledError, Exception):  # noqa: BLE001 — la fermeture ne doit jamais échouer
                pass

    # ---- interne -----------------------------------------------------------------------------------------------

    def _enqueue(self, chunk: bytes) -> None:
        if self._queue.qsize() >= QUEUE_MAX:
            try:
                self._queue.get_nowait()
                self.counters.dropped_chunks += 1
            except asyncio.QueueEmpty:
                pass
        self._queue.put_nowait(chunk)

    def _remember(self, chunk: bytes) -> None:
        self._ring.append(chunk)
        self._ring_bytes += len(chunk)
        while self._ring and self._ring_bytes - len(self._ring[0]) >= RING_BYTES:
            self._ring_bytes -= len(self._ring.popleft())

    def _forget_confirmed(self) -> None:
        self._ring.clear()
        self._ring_bytes = 0

    async def _run(self) -> None:
        failures = 0
        while not self._closing:
            try:
                stt = await self._gateway.open_stt(self._ticket)
            except TicketRejected:
                await self._events.state("closed", "ticket")
                return
            except GatewayUnavailable as exc:
                failures += 1
                if failures >= MAX_CONSECUTIVE_FAILURES:
                    await self._events.state("closed", f"passerelle {exc}")
                    return
                await asyncio.sleep(self._retry_delay_s * failures)
                continue
            opened_at = self._clock()
            started = False
            try:
                ws = await self._connect(provider_url(stt))
                try:
                    first = await asyncio.wait_for(ws.recv(), START_TIMEOUT_S)
                    kind, _ = classify(first)
                    if kind != "started":
                        self.last_close = kind.removeprefix("error:")
                        raise ConnectionError(kind)
                    started = True
                    failures = 0
                    self.counters.sessions += 1
                    reopen = self.counters.sessions > 1
                    if reopen:
                        self.counters.reopens += 1
                    await self._events.state("reopen" if reopen else "open", f"session={self.counters.sessions}")
                    await self._exchange(ws, replay=reopen)
                finally:
                    await _quiet_close(ws)
            except asyncio.CancelledError:
                await self._account(stt, opened_at)
                raise
            except Exception as exc:  # noqa: BLE001 — toute fin de session mène à une reprise bornée
                if not started:
                    failures += 1
                self.last_close = self.last_close or type(exc).__name__
            await self._account(stt, opened_at)
            if self._closing:
                return
            log.info("session d'écoute fermée (%s), reprise", self.last_close)
            await self._events.state("closing", self.last_close or "fin")
            self.last_close = None
            if failures >= MAX_CONSECUTIVE_FAILURES:
                await self._events.state("closed", "fournisseur")
                return
            await asyncio.sleep(self._retry_delay_s * max(1, failures))

    async def _account(self, stt: SttTicket, opened_at: float) -> None:
        await self._gateway.close_stt(self._ticket, stt.session_id, self._clock() - opened_at)

    async def _exchange(self, ws, replay: bool) -> None:
        if replay and self._ring:
            for chunk in list(self._ring):
                await ws.send(audio_message(chunk))
                self.counters.replayed_bytes += len(chunk)
                await asyncio.sleep(self._replay_gap_s)
        receiver = asyncio.create_task(self._receive(ws))
        try:
            while not receiver.done():
                getter = asyncio.create_task(self._queue.get())
                committer = asyncio.create_task(self._commit_requested.wait())
                done, _ = await asyncio.wait({getter, committer, receiver}, return_when=asyncio.FIRST_COMPLETED)
                if getter in done:
                    chunk = getter.result()
                    await ws.send(audio_message(chunk))
                    self._remember(chunk)
                else:
                    getter.cancel()
                    # Un bloc déjà retiré de la file pendant l'annulation est remis en tête de la suite.
                    if getter.done() and not getter.cancelled():
                        self._queue.put_nowait(getter.result())
                if committer in done:
                    self._commit_requested.clear()
                    while not self._queue.empty():
                        chunk = self._queue.get_nowait()
                        await ws.send(audio_message(chunk))
                        self._remember(chunk)
                    await ws.send(audio_message(b"", commit=True))
                else:
                    committer.cancel()
            receiver.result()
        finally:
            receiver.cancel()

    async def _receive(self, ws) -> None:
        async for message in ws:
            if not isinstance(message, str):
                continue
            kind, text = classify(message)
            if kind == "partial":
                await self._events.partial(text)
            elif kind == "final":
                self._forget_confirmed()
                if text.strip():
                    self.counters.finals += 1
                    await self._events.final(text)
                else:
                    self.counters.empty_finals += 1
            elif kind.startswith("error:"):
                self.last_close = kind[len("error:"):]
        code = getattr(ws, "close_code", None)
        if self.last_close is None:
            self.last_close = f"code={code}"


async def _quiet_close(ws) -> None:
    try:
        await ws.close()
    except Exception:  # noqa: BLE001
        pass
