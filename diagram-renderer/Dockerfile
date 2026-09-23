# F-142 / SF-142-06 — le service de rendu de diagrammes.
#
# Il vit DANS NOTRE CLUSTER pour que le poste du client n'installe rien : ni node, ni npm, ni chromium.
# C'est exactement ce que l'ancienne chaîne demandait au poste, et ce qu'un poste de banque refuse.
#
# Alpine + le chromium du système : puppeteer ne télécharge RIEN au build (le téléchargement était,
# chez le client, la première des trois barrières).
FROM node:22-alpine

ENV PUPPETEER_SKIP_DOWNLOAD=true \
    CHROME_BIN=/usr/bin/chromium \
    NODE_ENV=production

# F-142 / SF-142-07 : python + graphviz + `diagrams` pour les ICÔNES OFFICIELLES (AWS/Azure/GCP/
# on-prem). Ici aussi, c'est la gateway qui rend — le poste du client n'installe rien.
RUN apk add --no-cache chromium ttf-freefont font-noto font-noto-emoji \
        python3 py3-pip graphviz ttf-dejavu \
    && npm install -g @mermaid-js/mermaid-cli@11.4.2 \
    && pip install --no-cache-dir --break-system-packages diagrams==0.24.4 python-pptx==1.0.2 \
    && adduser -D -u 10001 renderer

WORKDIR /app
COPY server.js puppeteer.json cloud.py deck.py check_catalog.py ./
# Le catalogue d'icônes est vérifié AU BUILD : une entrée qui ne désigne aucune classe réelle ferait
# échouer le premier schéma d'un client, et pas la construction de l'image. On le refuse ici.
RUN python3 check_catalog.py && chown -R renderer:renderer /app

USER renderer
EXPOSE 8080
CMD ["node", "server.js"]
