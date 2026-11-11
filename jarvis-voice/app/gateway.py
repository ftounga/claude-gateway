"""F-257 / SF-257-02 — la passerelle vue du service vocal.

Le service ne détient AUCUN secret : il vérifie le ticket de session vocale en le présentant à la passerelle
(`GET /api/jarvis/voice-session/me`), puis le garde en mémoire pour appeler, au nom de l'utilisateur, la liste fermée
des routes ouvertes à la portée `jarvis-voice`. Le ticket n'est jamais journalisé.
"""

from __future__ import annotations

from dataclasses import dataclass

import httpx


class TicketRejected(Exception):
    """La passerelle refuse le ticket (absent, expiré, altéré, logout-all, mauvaise portée)."""


class GatewayUnavailable(Exception):
    """La passerelle ne répond pas (réseau, 5xx, délai)."""


@dataclass(frozen=True)
class SttTicket:
    """F-257 / SF-257-04 — une session d'écoute longue ouverte par la passerelle (jeton éphémère, jamais journalisé)."""

    session_id: str
    url: str
    token: str
    max_seconds: int

    def __repr__(self) -> str:  # le jeton ne s'affiche jamais
        return f"SttTicket(session_id={self.session_id!r}, max_seconds={self.max_seconds})"


@dataclass(frozen=True)
class VoiceIdentity:
    """Ce que la passerelle dit d'un ticket valide. Aucun contenu de conversation."""

    user_id: str
    workspace_id: str | None
    expires_at: str | None


class GatewayClient:
    """Client HTTP minimal vers la passerelle (URL interne du cluster)."""

    def __init__(self, base_url: str, timeout_s: float = 5.0, transport: httpx.AsyncBaseTransport | None = None):
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout_s
        self._transport = transport

    def _client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=self._base_url, timeout=self._timeout, transport=self._transport)

    async def verify(self, ticket: str | None) -> VoiceIdentity:
        """Vérifie un ticket auprès de la passerelle.

        :raises TicketRejected: ticket absent ou refusé (401/403)
        :raises GatewayUnavailable: passerelle injoignable ou en erreur
        """
        if not ticket:
            raise TicketRejected("ticket absent")
        try:
            async with self._client() as client:
                resp = await client.get("/jarvis/voice-session/me", headers={"Authorization": f"Bearer {ticket}"})
        except httpx.HTTPError as exc:  # réseau, délai
            raise GatewayUnavailable(type(exc).__name__) from None
        if resp.status_code in (401, 403):
            raise TicketRejected(f"refus {resp.status_code}")
        if resp.status_code != 200:
            raise GatewayUnavailable(f"statut {resp.status_code}")
        try:
            body = resp.json()
            user_id = str(body["userId"])
        except (ValueError, KeyError, TypeError):
            raise GatewayUnavailable("réponse illisible") from None
        return VoiceIdentity(user_id=user_id, workspace_id=body.get("workspaceId"), expires_at=body.get("expiresAt"))

    async def open_stt(self, ticket: str) -> SttTicket:
        """F-257 / SF-257-04 — ouvre une session d'écoute longue (``POST /jarvis/stt-session {"mode":"long"}``).

        :raises TicketRejected: ticket refusé (401/403)
        :raises GatewayUnavailable: passerelle injoignable, plafond atteint (429), écoute indisponible (503)…
        """
        try:
            async with self._client() as client:
                resp = await client.post("/jarvis/stt-session", json={"mode": "long"},
                                         headers={"Authorization": f"Bearer {ticket}"})
        except httpx.HTTPError as exc:
            raise GatewayUnavailable(type(exc).__name__) from None
        if resp.status_code in (401, 403):
            raise TicketRejected(f"refus {resp.status_code}")
        if resp.status_code != 200:
            raise GatewayUnavailable(f"statut {resp.status_code}")
        try:
            body = resp.json()
            return SttTicket(session_id=str(body["sessionId"]), url=str(body["url"]), token=str(body["token"]),
                             max_seconds=int(body.get("maxSeconds") or 0))
        except (ValueError, KeyError, TypeError):
            raise GatewayUnavailable("réponse illisible") from None

    async def close_stt(self, ticket: str, session_id: str, seconds: float) -> None:
        """F-257 / SF-257-04 — clôt une session d'écoute et la fait compter à sa durée. Les erreurs sont avalées :
        une session non close est comptée par le balayage de la passerelle."""
        try:
            async with self._client() as client:
                await client.post(f"/jarvis/stt-session/{session_id}/close", json={"seconds": max(0, round(seconds))},
                                  headers={"Authorization": f"Bearer {ticket}"})
        except httpx.HTTPError:
            pass
