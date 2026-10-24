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
        python-docx==1.1.2 openpyxl==3.1.5 \
    && adduser -D -u 10001 renderer

# F-212 / SF-212-02 : les polices des terminaux imités par les captures de preuve — Ubuntu Mono
# (GNOME Terminal, licence Ubuntu Font) et Cascadia Mono (Windows Terminal, OFL), dans leurs versions
# Nerd Font (même dessin, glyphes en plus). Seules les variantes Mono Regular/Bold sont gardées.
# macOS : Menlo dérive de DejaVu Sans Mono, déjà installée (ttf-dejavu).
RUN apk add --no-cache fontconfig font-ubuntu-mono-nerd font-cascadia-code-nerd \
    && find /usr/share/fonts/ubuntu-mono-nerd -type f ! -name 'UbuntuMonoNerdFontMono-Regular.ttf' \
        ! -name 'UbuntuMonoNerdFontMono-Bold.ttf' -delete \
    && find /usr/share/fonts/cascadia-code-nerd -type f ! -name 'CaskaydiaCoveNerdFontMono-Regular.ttf' \
        ! -name 'CaskaydiaCoveNerdFontMono-Bold.ttf' -delete \
    && fc-cache -f

WORKDIR /app
# F-184 / SF-184-01 : puppeteer-core (sans navigateur téléchargé) pilote le chromium du système pour
# imprimer une page en PDF en interceptant chaque requête — le service reste hors ligne.
RUN npm install --omit=dev --no-audit --no-fund puppeteer-core@25.13.0
COPY server.js drawio.js pdf.js terminal.js icons.js puppeteer.json cloud.py deck.py office.py check_catalog.py ./
# Le catalogue d'icônes est vérifié AU BUILD : une entrée qui ne désigne aucune classe réelle ferait
# échouer le premier schéma d'un client, et pas la construction de l'image. On le refuse ici.
# F-215 / SF-215-03 : la route /icons (cours vidéo) lit les PNG de la même lib — vérifiée elle aussi au build.
RUN python3 check_catalog.py \
    && node -e "const i=require('./icons.js');if(i.search('aws balancer').length===0)process.exit(1)" \
    && chown -R renderer:renderer /app

USER renderer
EXPOSE 8080
CMD ["node", "server.js"]
