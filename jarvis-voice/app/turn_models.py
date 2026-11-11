"""F-257 / SF-257-05 — les deux modèles du tour, pris tels quels dans Pipecat (ADR-021) : VAD Silero et smart-turn v3.

Les modèles ONNX sont livrés dans le paquet ``pipecat-ai`` (vérifiés au build de l'image) et tournent sur CPU, un
fil chacun. Rien n'est téléchargé, rien ne sort du pod.
"""

from __future__ import annotations

import os

from pipecat.audio.turn.base_turn_analyzer import EndOfTurnState
from pipecat.audio.turn.smart_turn.base_smart_turn import SmartTurnParams
from pipecat.audio.turn.smart_turn.local_smart_turn_v3 import LocalSmartTurnAnalyzerV3
from pipecat.audio.vad.silero import SileroVADAnalyzer
from pipecat.audio.vad.vad_analyzer import VADParams, VADState

from .turn import SAMPLE_RATE


def _env(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except ValueError:
        return default


class SileroVad:
    """VAD Silero : parole quand l'état lissé est `SPEAKING` ou `STOPPING` (≈ 200 ms de marge à l'arrêt)."""

    def __init__(self) -> None:
        self._analyzer = SileroVADAnalyzer(sample_rate=SAMPLE_RATE, params=VADParams(
            confidence=_env("JARVIS_VOICE_VAD_CONFIDENCE", 0.5),
            start_secs=_env("JARVIS_VOICE_VAD_START_S", 0.1),
            stop_secs=_env("JARVIS_VOICE_VAD_STOP_S", 0.2),
            min_volume=_env("JARVIS_VOICE_VAD_MIN_VOLUME", 0.6)))
        self._analyzer.set_sample_rate(SAMPLE_RATE)

    async def speaking(self, pcm: bytes) -> bool:
        state = await self._analyzer.analyze_audio(pcm)
        return state in (VADState.SPEAKING, VADState.STOPPING)

    async def close(self) -> None:
        await self._analyzer.cleanup()


class SmartTurn:
    """smart-turn v3 (8 Mo, CPU) : juge sur l'audio des 8 dernières secondes si la phrase est finie."""

    def __init__(self) -> None:
        # stop_secs : le plafond propre au modèle, laissé au-dessus des nôtres (c'est `TurnDetector` qui tranche).
        self._analyzer = LocalSmartTurnAnalyzerV3(sample_rate=SAMPLE_RATE, params=SmartTurnParams(stop_secs=10))
        self._analyzer.set_sample_rate(SAMPLE_RATE)

    def append(self, pcm: bytes, is_speech: bool) -> None:
        self._analyzer.append_audio(pcm, is_speech)

    async def complete(self) -> tuple[bool, float | None]:
        state, metrics = await self._analyzer.analyze_end_of_turn()
        probability = getattr(metrics, "probability", None)
        return state == EndOfTurnState.COMPLETE, probability

    def clear(self) -> None:
        self._analyzer.clear()

    async def close(self) -> None:
        await self._analyzer.cleanup()
