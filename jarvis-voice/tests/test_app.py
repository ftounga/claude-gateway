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
    client = TestClient(create_app(gw, 2))
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok", "sessions": 0}


def test_ready_ping_and_audio_counted():
    gw, seen = fake_gateway()
    app = create_app(gw, 2)
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
    client = TestClient(create_app(gw, 2))
    assert close_code(client.websocket_connect("/voice/ws"),
                      '{"type":"hello","ticket":"perime"}') == CLOSE_TICKET_REJECTED


def test_missing_ticket_closes_4401():
    gw, seen = fake_gateway()
    client = TestClient(create_app(gw, 2))
    assert close_code(client.websocket_connect("/voice/ws"), '{"type":"hello"}') == CLOSE_TICKET_REJECTED
    assert seen == []


def test_bad_hello_closes_4400():
    gw, _ = fake_gateway()
    client = TestClient(create_app(gw, 2))
    assert close_code(client.websocket_connect("/voice/ws"), "pas du json") == CLOSE_BAD_HELLO
    assert close_code(client.websocket_connect("/voice/ws"), '{"type":"ping"}') == CLOSE_BAD_HELLO


def test_gateway_down_closes_4503():
    gw, _ = fake_gateway(fail=True)
    client = TestClient(create_app(gw, 2))
    assert close_code(client.websocket_connect("/voice/ws"), '{"type":"hello","ticket":"t"}') == CLOSE_GATEWAY_DOWN
    gw500, _ = fake_gateway(status=500)
    client = TestClient(create_app(gw500, 2))
    assert close_code(client.websocket_connect("/voice/ws"), '{"type":"hello","ticket":"t"}') == CLOSE_GATEWAY_DOWN


def test_full_service_closes_4429():
    gw, _ = fake_gateway()
    client = TestClient(create_app(gw, 1))
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
