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

RUN apk add --no-cache chromium ttf-freefont font-noto font-noto-emoji \
    && npm install -g @mermaid-js/mermaid-cli@11.4.2 \
    && adduser -D -u 10001 renderer

WORKDIR /app
COPY server.js puppeteer.json ./
RUN chown -R renderer:renderer /app

USER renderer
EXPOSE 8080
CMD ["node", "server.js"]
