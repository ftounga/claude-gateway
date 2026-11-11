"""F-257 / SF-257-05 — le tour décidé côté serveur : parole, candidat, annulation, confirmation, plafonds, bruit."""

import pytest

from app.turn import (CEILING_S, CONFIRM_SILENCE_S, FINAL_TIMEOUT_S, SUSPENDED_EXTRA_S, Phase, TurnDetector,
                      is_suspended)

BLOCK = b"\x00\x00" * 1600  # 100 ms


class ScriptedVad:
    """Un VAD dont on dicte la réponse."""

    def __init__(self):
        self.speech = False

    async def speaking(self, pcm):
        return self.speech


class ScriptedModel:
    def __init__(self, complete=True, probability=0.97):
        self.result = (complete, probability)
        self.calls = 0
        self.cleared = 0
        self.appended = 0

    def append(self, pcm, is_speech):
        self.appended += 1

    async def complete(self):
        self.calls += 1
        return self.result

    def clear(self):
        self.cleared += 1


class Events:
    def __init__(self):
        self.log = []

    async def turn(self, state, **fields):
        self.log.append((state, fields))

    def states(self):
        return [s for s, _ in self.log]


def harness(complete=True):
    t = {"now": 0.0}
    vad, model, events, commits = ScriptedVad(), ScriptedModel(complete), Events(), []
    det = TurnDetector(vad, model, lambda: commits.append(t["now"]), events, clock=lambda: t["now"])

    async def feed(seconds, speech):
        vad.speech = speech
        for _ in range(round(seconds * 10)):
            await det.audio(BLOCK)
            t["now"] += 0.1

    return det, vad, model, events, commits, feed, t


@pytest.mark.asyncio
async def test_finished_sentence_candidate_then_confirmed_after_grace():
    det, vad, model, events, commits, feed, t = harness(complete=True)
    await feed(1.5, True)
    assert events.states() == ["speech"]
    await feed(0.1, False)
    assert events.states() == ["speech", "candidate"]
    assert events.log[1][1]["by"] == "modele" and events.log[1][1]["probability"] == 0.97
    assert len(commits) == 1
    await det.final("Bonjour Jarvis.")
    # Le texte est là, mais la grâce n'est pas écoulée : pas encore confirmé.
    assert events.states()[-1] == "candidate"
    await feed(CONFIRM_SILENCE_S, False)
    assert events.states()[-1] == "confirmed"
    assert events.log[-1][1]["text"] == "Bonjour Jarvis." and events.log[-1][1]["via"] == "final"
    assert det.phase is Phase.IDLE and model.cleared == 1
    assert det.counters.confirmed == 1 and det.counters.by_model == 1


@pytest.mark.asyncio
async def test_speech_resuming_cancels_the_candidate_and_keeps_the_text():
    det, vad, model, events, commits, feed, t = harness(complete=True)
    await feed(1.0, True)
    await feed(0.1, False)
    await det.final("Attends !")
    await feed(0.2, False)
    await feed(1.0, True)
    assert "cancelled" in events.states()
    await feed(0.1, False)
    await det.final("En fait, parle-moi du WAF.")
    await feed(CONFIRM_SILENCE_S, False)
    assert events.log[-1][0] == "confirmed"
    assert events.log[-1][1]["text"] == "Attends ! En fait, parle-moi du WAF."
    assert det.counters.cancelled == 1 and det.counters.confirmed == 1 and len(commits) == 2


@pytest.mark.asyncio
async def test_unfinished_sentence_waits_for_the_ceiling():
    det, vad, model, events, commits, feed, t = harness(complete=False)
    await feed(1.0, True)
    await feed(CEILING_S - 0.2, False)
    assert "candidate" not in events.states()
    await feed(0.3, False)
    assert events.states()[-1] == "candidate" and events.log[-1][1]["by"] == "plafond"


@pytest.mark.asyncio
async def test_suspended_sentence_waits_longer_even_if_the_model_says_finished():
    det, vad, model, events, commits, feed, t = harness(complete=True)
    await feed(1.0, True)
    await det.partial("Allons chez le client et")
    await feed(CEILING_S + 0.1, False)
    assert "candidate" not in events.states()
    await feed(SUSPENDED_EXTRA_S, False)
    assert events.states()[-1] == "candidate" and events.log[-1][1]["by"] == "plafond"


@pytest.mark.asyncio
async def test_short_noise_opens_no_turn():
    det, vad, model, events, commits, feed, t = harness()
    await feed(0.1, True)
    await feed(2.0, False)
    assert events.log == [] and commits == [] and det.counters.noise == 1 and det.counters.turns == 0


@pytest.mark.asyncio
async def test_a_partial_opens_the_turn_when_the_vad_missed_a_short_word():
    det, vad, model, events, commits, feed, t = harness(complete=False)
    await det.partial("Oui")
    assert events.log[0] == ("speech", {"via": "texte"})
    await feed(CEILING_S + 0.1, False)
    assert events.states()[-1] == "candidate"
    await det.final("Oui.")
    assert events.log[-1][0] == "confirmed" and events.log[-1][1]["text"] == "Oui."


@pytest.mark.asyncio
async def test_empty_turn_is_rejected_and_late_final_times_out():
    det, vad, model, events, commits, feed, t = harness(complete=True)
    await feed(0.5, True)
    await feed(0.1, False)
    await det.final("")
    await feed(CONFIRM_SILENCE_S, False)
    assert events.log[-1] == ("rejected", events.log[-1][1]) and events.log[-1][1]["reason"] == "empty"

    await feed(0.5, True)
    await feed(0.1, False)
    await det.partial("ignoré")  # partiel seul : jamais confirmé comme texte
    t["now"] += FINAL_TIMEOUT_S
    await det.tick()
    assert events.log[-1][0] == "rejected" and events.log[-1][1]["via"] == "delai"


def test_is_suspended_matches_the_screen_rule():
    assert is_suspended("Allons chez le client et")
    assert is_suspended("Il faut voir avec le WAF,")
    assert is_suspended("Euh…")
    assert not is_suspended("Où en est le sujet ?")
    assert not is_suspended("Montre-moi les clients")
    assert not is_suspended("")
    assert is_suspended("Parle-moi de l'")
