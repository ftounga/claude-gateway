"""F-257 / SF-257-02 — la porte du service vocal : santé, ticket vérifié par la passerelle, plafond, horodatages."""

import httpx
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.gateway import GatewayClient, GatewayUnavailable, TicketRejected
from app.main import (CLOSE_BAD_HELLO, CLOSE_FULL, CLOSE_GATEWAY_DOWN, CLOSE_TICKET_REJECTED, create_app)
from app.session import MAX_AUDIO_FRAME_BYTES


def fake_gateway(status=200, body=None, fail=False):
    """Une passerelle simulée : ``GET /jarvis/voice-session/me`` rend ``status``/``body`` (ou échoue)."""
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if fail:
            raise httpx.ConnectError("refusé", request=request)
        return httpx.Response(status, json=body if body is not None else {
            "userId": "u-1", "workspaceId": "w-1", "expiresAt": "2026-11-11T15:00:00Z"})

    return GatewayClient("http://gw/api", transport=httpx.MockTransport(handler)), seen


def close_code(ws_ctx, hello):
    with pytest.raises(WebSocketDisconnect) as err:
        with ws_ctx as ws:
            if hello is not None:
                ws.send_text(hello)
            ws.receive_json()
    return err.value.code


def test_health():
    gw, _ = fake_gateway()
    client = TestClient(create_app(gw, 2, stt_factory=False, turn_factory=False))
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok", "sessions": 0}


def test_ready_ping_and_audio_counted():
    gw, seen = fake_gateway()
    app = create_app(gw, 2, stt_factory=False, turn_factory=False)
    client = TestClient(app)
    with client.websocket_connect("/voice/ws") as ws:
        ws.send_json({"type": "hello", "ticket": "t-abc"})
        ready = ws.receive_json()
        assert ready["type"] == "ready" and ready["sessionId"] and ready["serverTs"] > 0
        ws.send_bytes(b"\x00\x00" * 1600)
        ws.send_bytes(b"\x00" * (MAX_AUDIO_FRAME_BYTES + 1))
        ws.send_json({"type": "ping", "clientTs": 42})
        pong = ws.receive_json()
        assert pong["type"] == "pong" and pong["clientTs"] == 42 and pong["serverTs"] >= ready["serverTs"]
        assert len(app.state.registry) == 1
        session = next(iter(app.state.registry._sessions.values()))
        assert session.audio_frames == 1 and session.audio_bytes == 3200 and session.oversized_frames == 1
        assert session.identity.user_id == "u-1" and session.identity.workspace_id == "w-1"
    # Le ticket est présenté en en-tête Bearer, jamais dans l'URL.
    assert seen[0].headers["Authorization"] == "Bearer t-abc"
    assert "t-abc" not in str(seen[0].url)
    assert len(app.state.registry) == 0


def test_ticket_rejected_closes_4401():
    gw, _ = fake_gateway(status=401)
    client = TestClient(create_app(gw, 2, stt_factory=False, turn_factory=False))
    assert close_code(client.websocket_connect("/voice/ws"),
                      '{"type":"hello","ticket":"perime"}') == CLOSE_TICKET_REJECTED


def test_missing_ticket_closes_4401():
    gw, seen = fake_gateway()
    client = TestClient(create_app(gw, 2, stt_factory=False, turn_factory=False))
    assert close_code(client.websocket_connect("/voice/ws"), '{"type":"hello"}') == CLOSE_TICKET_REJECTED
    assert seen == []


def test_bad_hello_closes_4400():
    gw, _ = fake_gateway()
    client = TestClient(create_app(gw, 2, stt_factory=False, turn_factory=False))
    assert close_code(client.websocket_connect("/voice/ws"), "pas du json") == CLOSE_BAD_HELLO
    assert close_code(client.websocket_connect("/voice/ws"), '{"type":"ping"}') == CLOSE_BAD_HELLO


def test_gateway_down_closes_4503():
    gw, _ = fake_gateway(fail=True)
    client = TestClient(create_app(gw, 2, stt_factory=False, turn_factory=False))
    assert close_code(client.websocket_connect("/voice/ws"), '{"type":"hello","ticket":"t"}') == CLOSE_GATEWAY_DOWN
    gw500, _ = fake_gateway(status=500)
    client = TestClient(create_app(gw500, 2, stt_factory=False, turn_factory=False))
    assert close_code(client.websocket_connect("/voice/ws"), '{"type":"hello","ticket":"t"}') == CLOSE_GATEWAY_DOWN


def test_full_service_closes_4429():
    gw, _ = fake_gateway()
    client = TestClient(create_app(gw, 1, stt_factory=False, turn_factory=False))
    with client.websocket_connect("/voice/ws") as first:
        first.send_json({"type": "hello", "ticket": "t1"})
        assert first.receive_json()["type"] == "ready"
        assert close_code(client.websocket_connect("/voice/ws"), '{"type":"hello","ticket":"t2"}') == CLOSE_FULL


@pytest.mark.asyncio
async def test_gateway_client_errors():
    gw, _ = fake_gateway(status=403)
    with pytest.raises(TicketRejected):
        await gw.verify("t")
    with pytest.raises(TicketRejected):
        await gw.verify(None)
    gw, _ = fake_gateway(body={"autre": 1})
    with pytest.raises(GatewayUnavailable):
        await gw.verify("t")


class FakeStt:
    """Une session d'écoute simulée : ce que le service lui pousse ; ses événements remontent à l'écran."""

    instances = []

    def __init__(self, gateway, ticket, events):
        self.ticket = ticket
        self.events = events
        self.pushed = []
        self.started = False
        self.closed = False
        FakeStt.instances.append(self)

    def start(self):
        self.started = True

    def push(self, pcm):
        self.pushed.append(pcm)
        # Le premier bloc entendu donne un texte final, puis une reprise de session.
        if len(self.pushed) == 1:
            import asyncio
            loop = asyncio.get_running_loop()
            loop.create_task(self._emit())

    async def _emit(self):
        await self.events.final("bonjour Jarvis")
        await self.events.state("reopen", "session=2")

    async def close(self):
        self.closed = True


def test_audio_goes_to_the_long_session_and_transcripts_come_back():
    FakeStt.instances.clear()
    gw, _ = fake_gateway()
    client = TestClient(create_app(gw, 2, stt_factory=FakeStt, turn_factory=False))
    with client.websocket_connect("/voice/ws") as ws:
        ws.send_json({"type": "hello", "ticket": "t-abc"})
        assert ws.receive_json()["type"] == "ready"
        stt = FakeStt.instances[0]
        assert stt.started and stt.ticket == "t-abc"
        ws.send_bytes(b"\x00\x01" * 160)
        ws.send_bytes(b"\x00" * (MAX_AUDIO_FRAME_BYTES + 1))  # trop grand : ni compté ni transmis
        event = ws.receive_json()
        assert event["type"] == "transcript" and event["final"] is True and event["text"] == "bonjour Jarvis"
        assert ws.receive_json()["state"] == "reopen"
        assert stt.pushed == [b"\x00\x01" * 160]
    assert FakeStt.instances[0].closed


class CommittingStt:
    """F-257 / SF-257-05 — une session d'écoute qui rend le texte final quand le tour la `commit`."""

    instances = []

    def __init__(self, gateway, ticket, events):
        self.events = events
        self.commits = 0
        CommittingStt.instances.append(self)

    def start(self):
        pass

    def push(self, pcm):
        pass

    def commit(self):
        import asyncio
        self.commits += 1
        asyncio.get_running_loop().create_task(self.events.final("Montre-moi les clients."))

    async def close(self):
        pass


def test_the_server_decides_the_turn_and_confirms_it_with_the_final_text():
    from app.turn import TurnDetector

    class LoudVad:
        async def speaking(self, pcm):
            return pcm[:1] != b"\x00"

    class Finished:
        def append(self, pcm, is_speech):
            pass

        async def complete(self):
            return True, 0.98

        def clear(self):
            pass

    CommittingStt.instances.clear()
    gw, _ = fake_gateway()
    client = TestClient(create_app(gw, 2, stt_factory=CommittingStt,
                                   turn_factory=lambda commit, events: TurnDetector(LoudVad(), Finished(), commit,
                                                                                    events)))
    with client.websocket_connect("/voice/ws") as ws:
        ws.send_json({"type": "hello", "ticket": "t"})
        assert ws.receive_json()["type"] == "ready"
        for _ in range(10):
            ws.send_bytes(b"\x01\x00" * 1600)  # 1 s de parole
        for _ in range(8):
            ws.send_bytes(b"\x00\x00" * 1600)  # 0,8 s de silence
        seen = []
        while not seen or seen[-1]["state"] != "confirmed":
            event = ws.receive_json()
            if event["type"] == "turn":
                seen.append(event)
        assert [e["state"] for e in seen] == ["speech", "candidate", "confirmed"]
        assert seen[1]["by"] == "modele" and seen[2]["text"] == "Montre-moi les clients."
        assert CommittingStt.instances[0].commits == 1


@pytest.mark.asyncio
async def test_gateway_stt_open_and_close():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if request.url.path.endswith("/close"):
            return httpx.Response(204)
        return httpx.Response(200, json={"sessionId": "s1", "url": "wss://p/r?commit_strategy=manual",
                                         "token": "jeton", "maxSeconds": 1800})

    gw = GatewayClient("http://gw/api", transport=httpx.MockTransport(handler))
    stt = await gw.open_stt("t-voix")
    assert (stt.session_id, stt.max_seconds) == ("s1", 1800)
    assert seen[0].url.path == "/api/jarvis/stt-session"
    assert seen[0].headers["Authorization"] == "Bearer t-voix"
    assert b'"long"' in seen[0].content
    await gw.close_stt("t-voix", "s1", 61.6)
    assert seen[1].url.path == "/api/jarvis/stt-session/s1/close" and b'"seconds":62' in seen[1].content
