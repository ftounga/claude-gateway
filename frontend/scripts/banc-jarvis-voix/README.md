# Banc à vraie voix de Jarvis (F-256)

La **vraie** page `/jarvis` (build de développement) dans Chrome sans tête, contre une **passerelle locale** branchée sur
les **vrais** fournisseurs, à qui l'on fait **entendre** des enregistrements. Cadrage :
`docs/features/F-256/CADRAGE-F-256-banc-a-vraie-voix.md`.

```bash
# une fois : puppeteer-core hors du dépôt
mkdir -p ~/.local/share/claude-gateway/banc-jarvis-voix-deps && cd $_ && npm init -y && npm i puppeteer-core@23.11.1

# le banc (Node 22 et Java 21 dans le PATH, kubectl sur le cluster en lecture)
frontend/scripts/banc-jarvis-voix/lancer.sh --build                      # construit jar + page, puis joue tout
frontend/scripts/banc-jarvis-voix/lancer.sh --scenarios conversation,ecran  # quelques scénarios
node frontend/scripts/banc-jarvis-voix/chrono.mjs <rapport.json> [scénario]  # comprendre un tour
node --test frontend/scripts/banc-jarvis-voix/mesure.test.mjs                # tests de la mesure
```

| Fichier | Rôle |
|---|---|
| `lancer.sh` | tout en une commande ; code 0 si la porte est franchie |
| `passerelle.sh` | passerelle locale : profil `dev`, H2 neuve, config non secrète de la prod, clés lues **à la volée** |
| `amorce.sh` | compte de test ADMIN + univers fictif (CAGIP > agenor, data-platform ; EDENRED > waf) |
| `avec-cles.sh` | lance une commande avec la clé ElevenLabs lue à la volée (`ELEVEN`) |
| `corpus.mjs`, `phrases.json` | corpus synthétique (voix ElevenLabs) et index des vraies captures ; scénarios |
| `micro.js` | micro virtuel et sondes, injectés avant la page (banc seulement) |
| `banc.mjs` | sert la page, relaie `/api`, joue les scénarios, écrit le rapport |
| `mesure.mjs`, `porte.mjs` | mesure tour par tour, totaux, résumé ; objectifs chiffrés |
| `chrono.mjs` | chronologie d'un passage (sondes, appels, journal d'écoute) |
| `relire.mjs` | relit un `rapport.json` avec la mesure et la porte actuelles (comparer sans rejouer ni repayer) |

Vue **2D par défaut** (`--vue 3d` pour la 3D) : sans GPU, la 3D en rendu logiciel occupe le fil principal et gonfle
les latences ; elle change aussi le comportement du flux (voir la référence dans le cadrage).

**Hors dépôt, toujours** : le corpus (`~/.local/share/claude-gateway/jarvis-voice-corpus/` — voix et contenu client),
les rapports (`~/.local/share/claude-gateway/banc-jarvis-voix/`), les clés (jamais écrites, affichées ni journalisées).
Une seule session de transcription à la fois (compte ElevenLabs partagé avec la prod).

Seuils de la porte réglables pour mesurer sans bloquer : `PORTE_ENTENDU_MIN=95`, `PORTE_PREMIER_SON_MAX_MS=2000`.
