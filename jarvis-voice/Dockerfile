# F-257 / SF-257-02 — le service vocal de Jarvis (ADR-021), sur le modèle de diagram-renderer.
#
# Signal et ordonnancement seulement : aucune clé fournisseur, aucun état durable. Les modèles de VAD (Silero) et de
# fin de tour (smart-turn v3, CPU) sont LIVRÉS DANS LE PAQUET pipecat-ai : rien n'est téléchargé au démarrage.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --uid 10001 --create-home jarvisvoice

COPY app ./app
# Les modèles sont vérifiés AU BUILD : un paquet sans ses ONNX ferait échouer la première conversation, pas l'image.
RUN python -c "import importlib.resources as r; \
assert r.files('pipecat.audio.vad.data').joinpath('silero_vad.onnx').is_file(); \
assert any(p.name.startswith('smart-turn-v3') for p in r.files('pipecat.audio.turn.smart_turn.data').iterdir()); \
import app.main" \
    && chown -R jarvisvoice:jarvisvoice /app

USER jarvisvoice
EXPOSE 8080
# Un seul processus : les sessions vivent en mémoire de ce processus (plafond JARVIS_VOICE_MAX_SESSIONS).
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--ws-ping-interval", "20", "--ws-ping-timeout", "20", "--no-access-log"]
