"""F-257 / SF-257-05 — **le serveur décide du tour** (cadrage §3.1, §4.1).

Le micro arrive en continu. Les décisions sont prises ici, plus dans le navigateur :

1. **Parole** : VAD Silero (Pipecat, CPU) ; un bruit plus court que ``MIN_SPEECH_S`` n'ouvre pas de tour. Un texte
   partiel de la session d'écoute ouvre aussi le tour (un « Oui. » bref que le VAD manque reste entendu).
2. **Fin de tour candidate** : quand la parole s'arrête, le détecteur **smart-turn v3** (Pipecat, CPU, français) juge
   sur l'**audio** si la phrase est finie. Oui, et la phrase écrite n'est pas « en suspens » (F-239 / SF-239-10) :
   **candidat** tout de suite. Sinon un **plafond de silence** tranche (``CEILING_S``, + ``SUSPENDED_EXTRA_S`` pour
   une phrase en suspens) — jamais de phrase coupée à la première pause de réflexion.
3. **Confirmation** : au candidat, la session d'écoute reçoit ``commit``. Le tour est **confirmé** quand le texte final
   est revenu ET que le silence dure depuis ``CONFIRM_SILENCE_S`` (la grâce de F-251, 0,7 s) ; si la parole reprend
   avant, le candidat est **annulé** et le tour continue (le texte déjà final est gardé). Un tour sans texte est
   **rejeté** (``empty``). Le texte final qui tarde au-delà de ``FINAL_TIMEOUT_S`` : on confirme sur ce qui est là.

Le candidat sert à démarrer le cerveau tôt (SF-257-06) ; rien n'est dit avant la confirmation (F-252 D1/D2).
Aucun contenu dans les journaux : états, durées, probabilités. Le texte confirmé ne part que vers l'écran.
"""

from __future__ import annotations

import os
import re
import time
import unicodedata
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Protocol

SAMPLE_RATE = 16_000
BYTES_PER_SECOND = SAMPLE_RATE * 2


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except ValueError:
        return default


#: Durée minimale de parole (VAD) pour qu'un tour existe.
MIN_SPEECH_S = _env_float("JARVIS_VOICE_MIN_SPEECH_S", 0.15)
#: Plafond de silence quand smart-turn juge la phrase inachevée (repli).
CEILING_S = _env_float("JARVIS_VOICE_CEILING_S", 2.0)
#: Une phrase en suspens attend autant de plus (F-239 / SF-239-10 : `SUSPENDED_EXTRA_MS` = 1 500).
SUSPENDED_EXTRA_S = _env_float("JARVIS_VOICE_SUSPENDED_EXTRA_S", 1.5)
#: Silence minimal avant de confirmer (la grâce F-251 : une parole qui reprend dans ce délai continue le tour).
CONFIRM_SILENCE_S = _env_float("JARVIS_VOICE_CONFIRM_SILENCE_S", 0.7)
#: Le texte final doit revenir dans ce délai après le `commit`, sinon on confirme sur ce qui est là.
FINAL_TIMEOUT_S = _env_float("JARVIS_VOICE_FINAL_TIMEOUT_S", 1.5)

#: Les derniers mots qui laissent une phrase ouverte (repris tels quels de `jarvis-turn-end.ts`).
OPEN_WORDS = frozenset("""
et ou mais donc or ni car puis alors que qui quoi dont si comme quand parce pour avec sans sur sous dans chez par vers
entre de du des a au aux en le la les l un une d mon ma mes ton ta tes son sa ses notre nos votre vos leur leurs ce cet
cette ces est sont euh heu hum bah ben enfin genre disons notamment surtout aussi meme tres plus moins""".split())


def is_suspended(text: str | None) -> bool:
    """Vrai si la phrase est restée ouverte (même règle que l'écran, `isSuspended`)."""
    t = (text or "").strip()
    if not t:
        return False
    if re.search(r"[-‐–—,;:]$", t) or t.endswith("...") or t.endswith("…"):
        return True
    if re.search(r"[.!?]$", t):
        return False
    flat = unicodedata.normalize("NFD", t.lower())
    flat = "".join(c for c in flat if not unicodedata.combining(c))
    flat = re.sub(r"['’]", " ", flat)
    words = re.sub(r"[^a-z0-9 ]+", " ", flat).split()
    return bool(words) and words[-1] in OPEN_WORDS


class Phase(Enum):
    IDLE = "idle"            # personne ne parle
    SPEAKING = "speaking"    # parole en cours
    PAUSED = "paused"        # silence après parole, fin pas encore décidée
    CANDIDATE = "candidate"  # fin décidée, commit envoyé, confirmation attendue


class Vad(Protocol):
    async def speaking(self, pcm: bytes) -> bool: ...


class EndOfTurnModel(Protocol):
    def append(self, pcm: bytes, is_speech: bool) -> None: ...

    async def complete(self) -> tuple[bool, float | None]: ...

    def clear(self) -> None: ...


class TurnEvents(Protocol):
    async def turn(self, state: str, **fields) -> None: ...


@dataclass
class TurnCounters:
    turns: int = 0
    candidates: int = 0
    cancelled: int = 0
    confirmed: int = 0
    rejected: int = 0
    by_model: int = 0
    by_ceiling: int = 0
    noise: int = 0


@dataclass
class _Turn:
    speech_s: float = 0.0
    silence_s: float = 0.0
    text: list[str] = field(default_factory=list)
    partial: str = ""
    candidate_at: float | None = None
    final_seen: bool = False
    decided_by: str = ""
    probability: float | None = None
    announced: bool = False

    def last_words(self) -> str:
        return self.partial or (self.text[-1] if self.text else "")


class TurnDetector:
    """L'orchestrateur du tour d'une conversation. ``commit`` demande le texte final à la session d'écoute."""

    def __init__(self, vad: Vad, model: EndOfTurnModel, commit: Callable[[], None], events: TurnEvents,
                 clock: Callable[[], float] = time.monotonic):
        self._vad = vad
        self._model = model
        self._commit = commit
        self._events = events
        self._clock = clock
        self.phase = Phase.IDLE
        self._turn: _Turn | None = None
        self.counters = TurnCounters()

    # ---- entrées -----------------------------------------------------------------------------------------------

    async def audio(self, pcm: bytes) -> None:
        """Un bloc du micro (PCM 16 bits mono 16 kHz)."""
        seconds = len(pcm) / BYTES_PER_SECOND
        speech = await self._vad.speaking(pcm)
        self._model.append(pcm, speech)
        if speech:
            await self._on_speech(seconds)
        elif self._turn is not None:
            await self._on_silence(seconds)

    async def partial(self, text: str) -> None:
        """Un texte partiel : il ouvre le tour si le VAD n'a rien vu (mot bref), et sert au test « en suspens »."""
        if not text.strip():
            return
        if self._turn is None:
            self._turn = _Turn(speech_s=MIN_SPEECH_S)
            self.counters.turns += 1
            self.phase = Phase.PAUSED
            self._turn.announced = True
            await self._events.turn("speech", via="texte")
        self._turn.partial = text

    async def final(self, text: str) -> None:
        """Un texte final (après `commit`, ou le commit automatique du fournisseur)."""
        turn = self._turn
        if turn is None:
            return
        if text.strip():
            turn.text.append(text.strip())
            turn.partial = ""
        if self.phase is Phase.CANDIDATE:
            turn.final_seen = True
            await self._maybe_confirm()

    async def tick(self) -> None:
        """Le temps passe : délai d'attente du texte final (appelé régulièrement par la conversation)."""
        await self._maybe_confirm()

    # ---- interne -----------------------------------------------------------------------------------------------

    async def _on_speech(self, seconds: float) -> None:
        if self._turn is None:
            self._turn = _Turn()
            self.counters.turns += 1
        turn = self._turn
        turn.speech_s += seconds
        turn.silence_s = 0.0
        if self.phase is Phase.CANDIDATE:
            # La parole reprend avant la confirmation : même demande, le tour continue.
            self.counters.cancelled += 1
            turn.candidate_at = None
            turn.final_seen = False
            await self._events.turn("cancelled", speechMs=round(turn.speech_s * 1000))
        self.phase = Phase.SPEAKING
        if not turn.announced and turn.speech_s >= MIN_SPEECH_S:
            turn.announced = True
            await self._events.turn("speech", via="vad")

    async def _on_silence(self, seconds: float) -> None:
        turn = self._turn
        assert turn is not None
        turn.silence_s += seconds
        if self.phase is Phase.SPEAKING:
            if turn.speech_s < MIN_SPEECH_S and not turn.partial and not turn.text:
                self.counters.noise += 1
                self.counters.turns -= 1
                self._reset()
                return
            self.phase = Phase.PAUSED
            done, probability = await self._model.complete()
            turn.probability = probability
            if done and not is_suspended(turn.last_words()):
                await self._candidate("modele")
                return
        if self.phase is Phase.PAUSED:
            ceiling = CEILING_S + (SUSPENDED_EXTRA_S if is_suspended(turn.last_words()) else 0.0)
            if turn.silence_s >= ceiling:
                await self._candidate("plafond")
        elif self.phase is Phase.CANDIDATE:
            await self._maybe_confirm()

    async def _candidate(self, by: str) -> None:
        turn = self._turn
        assert turn is not None
        self.phase = Phase.CANDIDATE
        turn.candidate_at = self._clock()
        turn.final_seen = False
        turn.decided_by = by
        self.counters.candidates += 1
        if by == "modele":
            self.counters.by_model += 1
        else:
            self.counters.by_ceiling += 1
        self._commit()
        # F-257 / SF-257-06 — le texte du moment (finals + partiel) : l'écran lance le cerveau rapide en provisoire
        # dessus (F-252) ; il n'est adopté que si le texte confirmé est le même. Jamais journalisé.
        heard = " ".join([*turn.text, turn.partial]).strip()
        await self._events.turn("candidate", text=heard, by=by, silenceMs=round(turn.silence_s * 1000),
                                speechMs=round(turn.speech_s * 1000),
                                probability=None if turn.probability is None else round(turn.probability, 3))

    async def _maybe_confirm(self) -> None:
        turn = self._turn
        if self.phase is not Phase.CANDIDATE or turn is None or turn.candidate_at is None:
            return
        waited = self._clock() - turn.candidate_at
        timed_out = waited >= FINAL_TIMEOUT_S
        if not (turn.final_seen or timed_out):
            return
        if turn.silence_s < CONFIRM_SILENCE_S and not timed_out:
            return
        text = " ".join(turn.text).strip()
        via = "final" if turn.final_seen else "delai"
        if text:
            self.counters.confirmed += 1
            await self._events.turn("confirmed", text=text, by=turn.decided_by, via=via,
                                    finalMs=round(waited * 1000), silenceMs=round(turn.silence_s * 1000),
                                    speechMs=round(turn.speech_s * 1000))
        else:
            self.counters.rejected += 1
            await self._events.turn("rejected", reason="empty", by=turn.decided_by, via=via,
                                    finalMs=round(waited * 1000))
        self._reset()

    def _reset(self) -> None:
        self._turn = None
        self.phase = Phase.IDLE
        self._model.clear()
