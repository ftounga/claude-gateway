"""F-257 / SF-257-02 — ``jarvis-voice`` : le service vocal de Jarvis (ADR-021).

Signal et ordonnancement seulement : aucune clé fournisseur, aucun état durable, aucun contenu journalisé. Cette
première version ouvre la porte (ticket vérifié par la passerelle, santé, plafond de sessions, horodatages serveur) ;
le pipeline Pipecat (écoute longue, fin de tour, préemption, voix, interruption) s'y branche en SF-257-04 →.

Protocole WebSocket ``/voice/ws`` (texte JSON + trames binaires PCM 16 kHz mono 16 bits) :

- le client envoie d'abord ``{"type":"hello","ticket":"…"}`` (dans les ``HELLO_TIMEOUT_S`` secondes) — le ticket ne
  passe jamais dans l'URL, donc jamais dans les journaux de l'ingress ;
- le service répond ``{"type":"ready","sessionId":…,"serverTs":…}`` ou ferme : 4401 ticket refusé, 4503 passerelle
  injoignable, 4429 service plein, 4400 premier message illisible ;
- ``{"type":"ping","clientTs":n}`` → ``{"type":"pong","clientTs":n,"serverTs":…}`` (crochet de mesure F-256) ;
- trames binaires : l'audio du micro.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from .gateway import GatewayClient, GatewayUnavailable, TicketRejected
from .session import SessionRegistry, VoiceSession, server_ts

log = logging.getLogger("jarvis-voice")

HELLO_TIMEOUT_S = 5.0

CLOSE_BAD_HELLO = 4400
CLOSE_TICKET_REJECTED = 4401
CLOSE_FULL = 4429
CLOSE_GATEWAY_DOWN = 4503


def create_app(gateway: GatewayClient | None = None, max_sessions: int | None = None) -> FastAPI:
    """Fabrique l'application (injectable pour les tests)."""
    gateway = gateway or GatewayClient(os.environ.get("JARVIS_VOICE_GATEWAY_URL",
                                                      "http://claude-gateway-backend:8080/api"))
    registry = SessionRegistry(max_sessions or int(os.environ.get("JARVIS_VOICE_MAX_SESSIONS", "8")))
    app = FastAPI(title="jarvis-voice", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.registry = registry

    @app.get("/health")
    async def health() -> dict:
        return {"status": "ok", "sessions": len(registry)}

    @app.websocket("/voice/ws")
    async def voice(ws: WebSocket) -> None:
        await ws.accept()
        try:
            hello = json.loads(await asyncio.wait_for(ws.receive_text(), HELLO_TIMEOUT_S))
            if not isinstance(hello, dict) or hello.get("type") != "hello":
                raise ValueError("premier message")
            ticket = hello.get("ticket")
        except (asyncio.TimeoutError, ValueError, KeyError, TypeError, WebSocketDisconnect, RuntimeError):
            await _close(ws, CLOSE_BAD_HELLO, "hello attendu")
            return
        if registry.full():
            log.info("session refusée : service plein (%d)", len(registry))
            await _close(ws, CLOSE_FULL, "service plein")
            return
        try:
            identity = await gateway.verify(ticket)
        except TicketRejected:
            await _close(ws, CLOSE_TICKET_REJECTED, "ticket refusé")
            return
        except GatewayUnavailable as exc:
            log.warning("passerelle injoignable à la vérification du ticket : %s", exc)
            await _close(ws, CLOSE_GATEWAY_DOWN, "passerelle injoignable")
            return
        session = VoiceSession(identity=identity, ticket=ticket)
        registry.add(session)
        log.info("session %s ouverte", session.session_id)
        try:
            await ws.send_json({"type": "ready", "sessionId": session.session_id, "serverTs": server_ts()})
            while True:
                message = await ws.receive()
                if message.get("type") == "websocket.disconnect":
                    break
                if message.get("bytes") is not None:
                    session.on_audio(message["bytes"])
                elif message.get("text") is not None:
                    await _on_text(ws, message["text"])
        except WebSocketDisconnect:
            pass
        finally:
            registry.remove(session)
            log.info("session %s fermée : %d trames audio, %d ignorées", session.session_id,
                     session.audio_frames, session.oversized_frames)

    return app


async def _on_text(ws: WebSocket, text: str) -> None:
    try:
        event = json.loads(text)
    except ValueError:
        return
    if isinstance(event, dict) and event.get("type") == "ping":
        await ws.send_json({"type": "pong", "clientTs": event.get("clientTs"), "serverTs": server_ts()})


async def _close(ws: WebSocket, code: int, reason: str) -> None:
    try:
        await ws.close(code=code, reason=reason)
    except RuntimeError:
        pass


app = create_app()
