# Mini-spec — [F-26 / SF-26-01] Copie de bloc (façon ChatGPT)

## Identifiant

`F-26 / SF-26-01`

## Feature parente

`F-26` — Copie de bloc (façon ChatGPT) : un bouton **« Copier »** directement sur chaque bloc
copiable d'une réponse de l'assistant (code, mail, config, doc…). Remplace le panneau Canvas
global (F-22).

## Statut

`ready`

## Date de création

2026-07-10

## Branche Git

`feat/SF-26-01-copie-de-bloc`

---

## Objectif

> En une phrase : afficher, directement sous chaque bloc de contenu généré par l'assistant dans le
> fil de chat, un bouton **« Copier »** qui place le contenu brut du bloc dans le presse-papiers —
> et retirer l'ancien panneau Canvas global (F-22).

---

## Comportement attendu

### Cas nominal

1. L'assistant renvoie une réponse contenant du texte et un ou plusieurs blocs délimités
   (fenced blocks Markdown ```` ```lang … ``` ````).
2. Le message est rendu comme une suite de **segments dans l'ordre d'origine** : le texte en prose
   est rendu en Markdown assaini (comme aujourd'hui) ; chaque bloc fenced est rendu par un
   composant dédié `app-copy-block` qui affiche une en-tête (icône + libellé du type/langage),
   le contenu **brut** en monospace, et un bouton **« Copier »** aligné à droite.
3. Au clic sur « Copier », le contenu brut du bloc est écrit dans le presse-papiers
   (`navigator.clipboard.writeText`) et une confirmation s'affiche via `MatSnackBar`.
4. Il n'y a **plus** de panneau latéral « Canevas », plus de bouton « Canevas » dans la barre
   d'outils, ni de bouton « Ouvrir dans le canevas » par message. L'agrégation globale est supprimée.

### Extraction & classification des blocs

- Un **bloc copiable** = un fenced code block d'un message dont `role = ASSISTANT`. Les messages
  utilisateur sont rendus littéralement (aucun bloc copiable, comportement inchangé).
- Le type est déduit du token de langage du fence (insensible à la casse) :
  - `mail`, `email`, `eml` → **mail**
  - `md`, `markdown`, `doc`, `document`, `text`, `txt`, `html` → **doc**
  - tout autre token (js, ts, python, java, sql, bash, json, css, xml, yaml…) ou absence de token → **code**
- Le libellé affiché : « Code (typescript) », « Document », « E-mail »… (langage repris pour le code).

### Cas d'erreur / dégradés

| Situation | Comportement attendu |
|-----------|----------------------|
| Message sans aucun bloc fenced | Rendu Markdown normal, aucun bouton « Copier » |
| Fenced block vide (aucune ligne de contenu) | Traité comme de la prose (pas de bloc copiable), rendu tel quel |
| Fenced block non clos (streaming en cours) | Traité comme de la prose tant que le bloc n'est pas fermé ; aucun bouton « Copier » partiel |
| `navigator.clipboard` indisponible (contexte non sécurisé) | Snackbar d'erreur « Copie impossible dans ce contexte. » ; aucune exception |
| Contenu Markdown hostile dans la prose (script, onerror) | Prose assainie via `renderMarkdown`/DOMPurify (aucune exécution) |
| Contenu de bloc hostile | Rendu en **texte brut** (interpolation Angular, jamais `innerHTML`) → jamais exécuté |

---

## Critères d'acceptation

- [ ] `splitMessageSegments` découpe le contenu d'un message assistant en segments ordonnés
      (prose / bloc), en préservant l'ordre d'origine.
- [ ] Chaque bloc fenced d'un message assistant produit un segment `block` avec type et libellé corrects.
- [ ] Un message sans bloc → un seul segment prose ; un message avec blocs → prose + blocs interleavés.
- [ ] Un fenced block vide ou non clos ne produit pas de bloc copiable (reste en prose).
- [ ] La classification code/doc/mail suit la table des tokens ci-dessus.
- [ ] Le composant `app-copy-block` affiche le contenu brut en monospace + un bouton « Copier ».
- [ ] Le bouton « Copier » écrit le contenu **brut** du bloc dans le presse-papiers et notifie via snackbar.
- [ ] En l'absence de `navigator.clipboard`, une erreur douce est affichée (pas d'exception).
- [ ] Le contenu du bloc est rendu par interpolation de texte (jamais `innerHTML`) ; la prose reste assainie.
- [ ] Le panneau Canvas (F-22), son bouton de barre d'outils, son badge et le bouton « Ouvrir dans
      le canevas » par message sont **supprimés** ; aucune régression du reste du chat.
- [ ] Le composant respecte `DESIGN_SYSTEM.md` (variables `--cg-*`, Material, espacements ×4px, polices autorisées).
- [ ] Les tests unitaires (`splitMessageSegments`), de composant (`CopyBlockComponent`) et de chat passent.

---

## Périmètre

### Hors scope (explicite)

- Aucune persistance : les blocs sont dérivés à la volée des messages déjà chargés (pas de table, pas d'endpoint).
- Aucune coloration syntaxique du code (monospace simple). Highlighter = évolution future.
- Aucun aperçu Markdown rendu **par bloc** (le bloc est présenté en source brute ; la prose autour
  reste rendue en Markdown). Voir arbitrage.
- Aucun appel réseau, aucun fournisseur IA : on présente le contenu déjà produit (Provider-First).
- Aucune édition de bloc ni renvoi au LLM.

---

## Valeurs initiales

Non applicable — aucune entité créée, aucun état serveur.

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|-------|-------------|-------|
| contenu bloc | Oui (non vide après trim) | un fence vide est traité comme prose |
| type | Oui | `code` \| `doc` \| `mail`, déduit du token de langage |
| copie | — | contenu **brut** du bloc, via `navigator.clipboard.writeText` |

Le contenu du bloc est **toujours** rendu par interpolation de texte Angular (jamais `innerHTML`).
La prose est **toujours** assainie via `renderMarkdown` (DOMPurify).

---

## Technique

### Endpoint(s)

Aucun. Feature 100 % frontend consommant les messages déjà chargés par F-02.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `shared/copy-block.model.ts` — types `CopyBlock`, `CopyBlockType`, `MessageSegment`.
- `shared/message-segments.ts` — fonction pure `splitMessageSegments(content): MessageSegment[]`
  + `copyBlockTypeFromLanguage` (logique testable sans TestBed).
- `shared/message-segments.pipe.ts` — pipe pur `messageSegments` (mémoïsé sur le contenu).
- `chat/copy-block/copy-block.component.ts|html|scss` — `CopyBlockComponent` (en-tête + source + copie).
- `chat/chat.component.*` — rendu segmenté des messages assistant ; **suppression** de l'intégration Canvas.
- **Suppressions** : `chat/artifact-panel/*`, `shared/artifact.ts`, `shared/artifact.model.ts`, `shared/artifact.spec.ts`.

---

## Plan de test

### Tests unitaires (logique pure, sans TestBed)

- [ ] `splitMessageSegments` — texte seul → 1 segment prose.
- [ ] `splitMessageSegments` — prose + 1 bloc code → segments `[prose, block]`, langage conservé.
- [ ] `splitMessageSegments` — blocs interleavés (texte, bloc, texte, bloc) → ordre préservé.
- [ ] `splitMessageSegments` — tokens `mail`/`email` → `mail` ; `markdown`/`html` → `doc` ; autre → `code`.
- [ ] `splitMessageSegments` — fence vide / non clos → aucun bloc (reste prose).
- [ ] `splitMessageSegments` — absence de token → type `code`, libellé « Code ».

### Tests de composant

- [ ] `CopyBlockComponent` — rend le libellé et le contenu brut du bloc.
- [ ] `CopyBlockComponent` — « Copier » appelle `navigator.clipboard.writeText` avec le contenu brut + snackbar.
- [ ] `CopyBlockComponent` — clipboard absent → snackbar d'erreur, pas d'exception.
- [ ] `ChatComponent` — un message assistant avec bloc rend un `app-copy-block` ; un fence dans un
      message utilisateur ne produit **pas** de bloc copiable ; les tests existants restent verts.

### Isolation utilisateur

- [x] Non applicable — aucune donnée serveur n'est lue ni écrite ; le composant n'opère que sur les
  messages de la conversation déjà chargée (isolation `user_id` garantie en amont par l'API F-02).

---

## Préoccupations transversales

| Préoccupation | Impact | Composants |
|---------------|--------|------------|
| Auth / Principal | Aucun | — |
| Contexte tenant | Aucun (pas d'accès données) | — |
| Plans / limites | Aucun | — |
| Navigation / routing | Aucune nouvelle route ; rendu interne à `/chat` ; suppression de la colonne Canvas | `chat.component` |

---

## Dépendances

### Subfeatures bloquantes

- `SF-02-02` (écran de chat) — **Done**. Fournit `ChatComponent`, `ChatMessage`, le pipe `markdown`.
- `F-22 / SF-22-01` (Canvas) — **Refondu** : cette SF le remplace et retire son code.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **Arbitrage (réversible)** : découpage F-26 en **une seule SF** frontend (slice vertical cohérent :
  extraction segmentée + composant bloc + retrait du Canvas). Précédent : F-22 à SF unique.
- **Arbitrage (réversible)** : approche **par segmentation du message** (prose / bloc rendus par des
  composants distincts) plutôt que post-traitement du DOM injecté via `innerHTML`. Motif : boutons
  Angular réels (testables), pas de manipulation manuelle du DOM, assainissement préservé.
  Alternative écartée : parcourir le DOM rendu pour y greffer des boutons → fragile, non testable proprement.
- **Arbitrage (réversible)** : chaque bloc est présenté en **source brute** (monospace) avec un bouton
  « Copier », façon ChatGPT — pas d'aperçu Markdown par bloc. L'aperçu Aperçu/Source appartenait au
  panneau Canvas (F-22) désormais retiré ; la prose autour du bloc reste rendue en Markdown.
  Alternative écartée : conserver un toggle Aperçu/Source par bloc → recrée la complexité du panneau, non ChatGPT-like.
- **Arbitrage (réversible)** : suppression complète du composant `artifact-panel` et des modèles
  `artifact.*` (remplacés par `copy-block.*` / `message-segments.*`). Motif : le Canvas est explicitement
  remplacé (F-22 « Refondu → F-26 ») ; conserver du code mort serait un smell de review.
- Provider-First respecté : aucune capacité IA réimplémentée ; on présente le contenu déjà produit.
