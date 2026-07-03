# Mini-spec — [F-22 / SF-22-01] Panneau Canvas / Artifacts (frontend)

## Identifiant

`F-22 / SF-22-01`

## Feature parente

`F-22` — Canvas / Artifacts : panneau latéral du contenu généré (code, doc, mail) avec copie et aperçu.

## Statut

`ready`

## Date de création

2026-07-03

## Branche Git

`feat/SF-22-01-panneau-canvas-artifacts`

---

## Objectif

> En une phrase : offrir dans l'écran de chat un panneau latéral (« canevas ») qui extrait les
> blocs de contenu générés par l'assistant (code, document, mail) et permet de les prévisualiser
> et de les copier en un clic.

---

## Comportement attendu

### Cas nominal

1. L'assistant renvoie une réponse contenant un ou plusieurs blocs de code délimités
   (fenced blocks Markdown ```` ```lang … ``` ````).
2. Un bouton « Canevas » apparaît dans la barre d'outils du chat, avec un badge indiquant le
   nombre d'artefacts détectés dans la conversation active.
3. Au clic, un panneau latéral s'ouvre à droite du fil de messages et liste les artefacts
   (titre + type : code / document / mail).
4. En sélectionnant un artefact, l'utilisateur voit :
   - son contenu en **Aperçu** (rendu Markdown assaini) pour les types `doc`/`mail`, ou en
     **Source** (texte brut monospace) ;
   - un bouton **Copier** qui place le contenu brut dans le presse-papiers et confirme via snackbar.
5. Un bouton « Ouvrir dans le canevas » sur chaque message assistant contenant au moins un
   artefact ouvre le panneau et sélectionne le premier artefact de ce message.
6. Le panneau se ferme via son bouton de fermeture ou en re-cliquant sur le bouton « Canevas ».

### Extraction & classification

- Un **artefact** = un fenced code block d'un message dont `role = ASSISTANT`. Les messages
  utilisateur sont ignorés.
- Le type est déduit du token de langage du fence (insensible à la casse) :
  - `mail`, `email`, `eml` → **mail**
  - `md`, `markdown`, `doc`, `document`, `text`, `txt`, `html` → **doc**
  - tout autre token (js, ts, python, java, sql, bash, json, css, xml, yaml, …) ou absence de
    token → **code**
- `id` stable : `` `${messageId}#${index}` `` (index = position du bloc dans le message).

### Cas d'erreur / dégradés

| Situation | Comportement attendu |
|-----------|----------------------|
| Aucun artefact dans la conversation | Bouton « Canevas » masqué ; panneau non ouvrable |
| Fenced block vide (aucune ligne de contenu) | Ignoré (pas d'artefact) |
| `navigator.clipboard` indisponible (contexte non sécurisé) | Snackbar d'erreur « Copie impossible » ; pas d'exception |
| Réponse en cours de streaming (fence non refermé) | Aucun artefact partiel affiché tant que le bloc n'est pas clos |
| Contenu Markdown hostile (script, onerror) | Aperçu assaini via `renderMarkdown`/DOMPurify (aucune exécution) |

---

## Critères d'acceptation

- [ ] `extractArtifacts` extrait chaque fenced block d'un message assistant comme artefact avec `id` stable.
- [ ] Les messages `USER` ne produisent aucun artefact.
- [ ] La classification code/doc/mail suit la table des tokens ci-dessus.
- [ ] Un fenced block non clos ou vide ne produit pas d'artefact.
- [ ] Le bouton « Canevas » n'apparaît que si au moins un artefact existe, et affiche le compte exact.
- [ ] L'aperçu des types `doc`/`mail` est rendu via `renderMarkdown` (assaini) ; la source est du texte brut non interprété.
- [ ] Le bouton **Copier** copie le contenu **brut** de l'artefact et notifie ; en l'absence de `clipboard`, une erreur douce est affichée (pas d'exception).
- [ ] Le panneau respecte `DESIGN_SYSTEM.md` (variables `--cg-*`, Material, espacements multiples de 4px).
- [ ] Les tests unitaires (`extractArtifacts`) et de composant passent ; les tests existants du chat restent verts.

---

## Périmètre

### Hors scope (explicite)

- Aucune persistance : les artefacts sont dérivés à la volée des messages déjà chargés (pas de table, pas d'endpoint).
- Aucune édition d'artefact (lecture seule ; pas de renvoi au LLM). L'édition inline est une évolution future.
- Pas de coloration syntaxique du code (monospace simple). Highlighter = évolution future.
- Pas d'export de l'artefact (l'export conversation existe déjà en F-14).
- Aucun appel réseau, aucun fournisseur IA : le composant ne communique avec aucun backend.

---

## Valeurs initiales

Non applicable — aucune entité créée, aucun état serveur.

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|-------|-------------|-------|
| contenu artefact | Oui (non vide après trim) | un fence vide est ignoré |
| type | Oui | `code` \| `doc` \| `mail`, déduit du token de langage |
| id | Oui | `${messageId}#${index}`, unique dans la conversation |

Le contenu affiché en Aperçu est **toujours** assaini via `renderMarkdown` (DOMPurify). La Source est
rendue par interpolation de texte Angular (jamais `innerHTML`).

---

## Technique

### Endpoint(s)

Aucun. Feature 100 % frontend consommant les messages déjà chargés par F-02 (`GET /conversations/{id}`).

### Tables impactées

Aucune. Aucune migration Liquibase.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `shared/artifact.model.ts` — types `Artifact`, `ArtifactType`.
- `shared/artifact.ts` — fonction pure `extractArtifacts(messages): Artifact[]` (logique testable sans TestBed).
- `chat/artifact-panel/artifact-panel.component.ts|html|scss` — `ArtifactPanelComponent` (liste + visualiseur + copie + toggle Aperçu/Source).
- `chat/chat.component.*` — intégration : signal `canvasOpen`, `computed artifacts`, bouton toolbar + badge, colonne latérale, bouton « Ouvrir dans le canevas » par message.

---

## Plan de test

### Tests unitaires (logique pure, sans TestBed)

- [ ] `extractArtifacts` — un bloc code → 1 artefact type `code`, langage conservé.
- [ ] `extractArtifacts` — tokens `mail`/`email` → type `mail` ; `markdown`/`html`/`doc` → type `doc`.
- [ ] `extractArtifacts` — messages `USER` ignorés.
- [ ] `extractArtifacts` — plusieurs blocs dans un message → ids `msg#0`, `msg#1` distincts.
- [ ] `extractArtifacts` — fence vide / non clos → aucun artefact.

### Tests de composant

- [ ] `ArtifactPanelComponent` — rend la liste et sélectionne un artefact.
- [ ] `ArtifactPanelComponent` — bouton Copier appelle `navigator.clipboard.writeText` avec le contenu brut.
- [ ] `ArtifactPanelComponent` — clipboard absent → snackbar d'erreur, pas d'exception.
- [ ] `ArtifactPanelComponent` — bascule Aperçu/Source pour un artefact `doc`.
- [ ] `ChatComponent` — le compte d'artefacts reflète les messages ; tests existants inchangés.

### Isolation utilisateur

- [x] Non applicable — aucune donnée serveur n'est lue ni écrite ; le composant n'opère que sur les
  messages de la conversation déjà chargée (isolation `user_id` déjà garantie par l'API F-02 en amont).

---

## Préoccupations transversales

| Préoccupation | Impact | Composants |
|---------------|--------|------------|
| Auth / Principal | Aucun | — |
| Contexte tenant | Aucun (pas d'accès données) | — |
| Plans / limites | Aucun | — |
| Navigation / routing | Aucune nouvelle route ; panneau interne à `/chat` | `chat.component` |

---

## Dépendances

### Subfeatures bloquantes

- `SF-02-02` (écran de chat) — **Done**. Fournit `ChatComponent`, `ChatMessage`, le pipe `markdown`.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **Arbitrage (réversible)** : découpage F-22 en **une seule SF** frontend. La feature est un slice
  vertical cohérent (extraction + panneau) sans backend ; précédent : F-15/F-19 à SF unique.
- **Arbitrage (réversible)** : un artefact = fenced code block Markdown de l'assistant. C'est la
  granularité la plus fiable et déterministe (pas d'heuristique floue). Alternative écartée :
  détection sémantique par le LLM (hors périmètre, coûteuse, non déterministe).
- **Arbitrage (réversible)** : Aperçu réservé aux types `doc`/`mail` ; le code reste en Source
  (pas de highlighter embarqué en V1).
- Provider-First respecté : aucune capacité IA réimplémentée ; on présente le contenu déjà produit.
