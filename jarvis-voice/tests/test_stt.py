"""F-257 / SF-257-04 — la session d'écoute longue : commit manuel, finals vides tolérés, reprise avec rejeu, comptage."""

import asyncio
import base64
import json

import pytest

from app.gateway import GatewayUnavailable, SttTicket, TicketRejected
from app.stt import (CHUNK_BYTES, MAX_CONSECUTIVE_FAILURES, RING_BYTES, LongTranscription, classify, provider_url)


class FakeProvider:
    """Un WebSocket du fournisseur : ce que le service envoie, et ce que le fournisseur répond."""

    def __init__(self, first='{"message_type":"session_started"}'):
        self.sent = []
        self.inbox: asyncio.Queue = asyncio.Queue()
        self.first = first
        self.close_code = None
        self.closed = False

    async def recv(self):
        return self.first

    async def send(self, message):
        self.sent.append(json.loads(message))

    def say(self, message):
        self.inbox.put_nowait(message)

    def hang_up(self, code=1000):
        self.close_code = code
        self.inbox.put_nowait(None)

    def __aiter__(self):
        return self

    async def __anext__(self):
        item = await self.inbox.get()
        if item is None:
            raise StopAsyncIteration
        return item

    async def close(self):
        self.closed = True

    def audio(self):
        return b"".join(base64.b64decode(m["audio_base_64"]) for m in self.sent if not m["commit"])

    def commits(self):
        return sum(1 for m in self.sent if m["commit"])


class FakeGateway:
    def __init__(self, fail=None):
        self.opened = []
        self.closed = []
        self.fail = list(fail or [])

    async def open_stt(self, ticket):
        if self.fail:
            raise self.fail.pop(0)
        n = len(self.opened) + 1
        self.opened.append(ticket)
        return SttTicket(session_id=f"s{n}", url="wss://p/realtime?commit_strategy=manual", token=f"jeton{n}",
                         max_seconds=1800)

    async def close_stt(self, ticket, session_id, seconds):
        self.closed.append((session_id, seconds))


class Events:
    def __init__(self):
        self.log = []

    async def partial(self, text):
        self.log.append(("partial", text))

    async def final(self, text):
        self.log.append(("final", text))

    async def state(self, state, detail):
        self.log.append(("state", state, detail))


def harness(providers, gateway=None):
    gateway = gateway or FakeGateway()
    events = Events()
    urls = []
    queue = list(providers)

    async def connect(url):
        urls.append(url)
        return queue.pop(0)

    clock = {"t": 0.0}
    stt = LongTranscription(gateway, "ticket-voix", events, connect=connect, clock=lambda: clock["t"],
                            replay_gap_s=0, retry_delay_s=0)
    return stt, gateway, events, urls, clock


async def settle(n=20):
    for _ in range(n):
        await asyncio.sleep(0)


@pytest.mark.asyncio
async def test_audio_grouped_commit_manual_and_finals():
    p = FakeProvider()
    stt, gw, events, urls, clock = harness([p])
    stt.start()
    await settle()
    assert urls == ["wss://p/realtime?commit_strategy=manual&token=jeton1"]
    assert ("state", "open", "session=1") in events.log
    for _ in range(25):  # 25 blocs de 128 échantillons = 6 400 octets
        stt.push(b"\x01\x00" * 128)
    await settle()
    assert [len(base64.b64decode(m["audio_base_64"])) for m in p.sent] == [CHUNK_BYTES, CHUNK_BYTES]
    stt.push(b"\x02\x00" * 10)
    stt.commit()
    await settle()
    assert p.commits() == 1
    assert len(p.audio()) == 6400 + 20
    p.say('{"message_type":"partial_transcript","text":"bonjour"}')
    p.say('{"message_type":"committed_transcript","text":""}')  # le commit automatique des 36 s
    p.say('{"message_type":"committed_transcript","text":"bonjour Jarvis"}')
    await settle()
    assert ("partial", "bonjour") in events.log
    assert [e for e in events.log if e[0] == "final"] == [("final", "bonjour Jarvis")]
    assert stt.counters.empty_finals == 1 and stt.counters.finals == 1
    clock["t"] = 42.0
    await stt.close()
    assert gw.closed == [("s1", 42.0)]
    assert p.closed


@pytest.mark.asyncio
async def test_reopen_replays_unconfirmed_audio_and_counts_each_session():
    p1, p2 = FakeProvider(), FakeProvider()
    stt, gw, events, urls, clock = harness([p1, p2])
    stt.start()
    await settle()
    stt.push(b"\x01\x00" * (CHUNK_BYTES // 2))
    await settle()
    p1.say('{"message_type":"committed_transcript","text":"phrase finie"}')
    await settle()
    stt.push(b"\x03\x00" * (CHUNK_BYTES // 2))  # envoyé, pas encore confirmé
    await settle()
    clock["t"] = 1470.0
    p1.say('{"message_type":"queue_overflow","error":"too frequent"}')
    p1.hang_up(1000)
    await settle(60)
    assert ("state", "closing", "queue_overflow") in events.log
    assert ("state", "reopen", "session=2") in events.log
    assert gw.closed[0] == ("s1", 1470.0)
    assert urls[1].endswith("token=jeton2")
    # Seul l'audio non confirmé est rejoué : la phrase finie ne l'est pas.
    assert p2.audio() == b"\x03\x00" * (CHUNK_BYTES // 2)
    assert stt.counters.reopens == 1 and stt.counters.replayed_bytes == CHUNK_BYTES
    await stt.close()


@pytest.mark.asyncio
async def test_ring_is_bounded_to_two_seconds():
    p1, p2 = FakeProvider(), FakeProvider()
    stt, gw, events, urls, clock = harness([p1, p2])
    stt.start()
    await settle()
    for _ in range(30):  # 3 s d'audio jamais confirmé
        stt.push(b"\x05\x00" * (CHUNK_BYTES // 2))
        await settle(3)
    p1.hang_up(1006)
    await settle(80)
    assert len(p2.audio()) <= RING_BYTES
    assert len(p2.audio()) >= RING_BYTES - CHUNK_BYTES
    await stt.close()


@pytest.mark.asyncio
async def test_refused_session_and_gateway_failures_are_bounded():
    refused = FakeProvider(first='{"message_type":"rate_limited","error":"15 concurrent"}')
    ok = FakeProvider()
    stt, gw, events, urls, clock = harness([refused, ok])
    stt.start()
    await settle(60)
    assert ("state", "closing", "rate_limited") in events.log
    assert ("state", "open", "session=1") in events.log
    await stt.close()

    gw2 = FakeGateway(fail=[GatewayUnavailable("503")] * MAX_CONSECUTIVE_FAILURES)
    stt2, _, events2, _, _ = harness([], gateway=gw2)
    stt2.start()
    await settle(60)
    assert events2.log[-1][:2] == ("state", "closed")

    gw3 = FakeGateway(fail=[TicketRejected("401")])
    stt3, _, events3, _, _ = harness([], gateway=gw3)
    stt3.start()
    await settle()
    assert events3.log == [("state", "closed", "ticket")]


def test_classify_never_returns_error_text_and_url_hides_nothing_else():
    assert classify('{"message_type":"queue_overflow","error":"il a dit secret"}') == ("error:queue_overflow", "")
    assert classify('{"message_type":"committed_transcript_with_timestamps","text":"x"}') == ("final", "x")
    assert classify("pas du json") == ("other", "")
    assert provider_url(SttTicket("s", "wss://p/r", "a b", 1)) == "wss://p/r?token=a%20b"
    assert "jeton" not in repr(SttTicket("s", "wss://p/r?x=1", "jeton", 1))
