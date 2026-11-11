"""F-257 / SF-257-05 — les vrais modèles (Silero, smart-turn v3) se chargent depuis le paquet et tiennent le rythme."""

import time

import pytest

from app.turn_models import SileroVad, SmartTurn


@pytest.mark.asyncio
async def test_models_load_and_silence_is_not_speech():
    vad, model = SileroVad(), SmartTurn()
    silence = b"\x00\x00" * 1600  # 100 ms
    started = time.perf_counter()
    for _ in range(20):  # 2 s
        assert await vad.speaking(silence) is False
        model.append(silence, False)
    elapsed = time.perf_counter() - started
    # Bien plus vite que le temps réel (2 s d'audio), même sur une petite machine de CI.
    assert elapsed < 2.0
    done, _ = await model.complete()
    assert done in (True, False)
    model.clear()
    await vad.close()
    await model.close()
