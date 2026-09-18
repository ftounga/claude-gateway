# Mini-spec — F-125 / SF-125-07 — Jamais un tour vide : conserver la réponse déjà produite

> Dérivée du cadrage (source de vérité) `docs/features/F-125/SF-125-07-jamais-un-tour-vide-filet-de-synthese.md`.
> Ceinture de sécurité complémentaire de SF-125-06 (cause racine déjà retirée).

---

## Identifiant

`F-125 / SF-125-07`

## Feature parente

`F-125` — La tenue de la carte du poste : silencieuse, robuste, jamais dans la réponse

## Statut

`ready`

## Date de création

2026-09-18

## Branche Git

`feat/SF-125-07-jamais-un-tour-vide`

---

## Objectif

Garantir côté harness qu'une **réponse déjà produite et streamée** à l'utilisateur n'est **jamais** remplacée par un message vide, et que — si vraiment rien n'a été produit — le serveur provoque **une** passe de synthèse avant tout message de dernier recours, en supprimant définitivement le placeholder « Je n'ai pas produit de réponse pour ce message. ».

---

## Comportement attendu

### Cas nominal

`AtelierChatService.runLoop` mémorise, au fil des itérations, le **dernier texte destiné à l'utilisateur non vide après strip** (`stripTurnMetadata`, sans toucher au bloc `<<essentiel>>` de F-126). À la **clôture normale** du tour (branche `turn.finished() || turn.toolCalls().isEmpty()`, après le crochet `END_OF_TURN`) :

1. Si le texte du dernier tour est non vide après strip → c'est la réponse (comportement d'avant).
2. Sinon, si un texte utilisateur a été mémorisé plus tôt dans le tour → **ce texte conservé est la réponse** ; une itération de plomberie postérieure sans texte ne peut jamais l'écraser par du vide.
3. Sinon (aucun texte de tout le tour) → **une passe de synthèse forcée UNIQUE** : le modèle est relancé une fois, sans outils (pas de plomberie) et sans redéclencher aucun crochet `END_OF_TURN`, avec la consigne « Le tour s'achève. Réponds maintenant, directement, à la dernière demande… ». Son texte (streamé pour que SSE et persistance convergent) devient la réponse.
4. Si la synthèse ne rend toujours rien → **message de dernier recours honnête et actionnable** (« Je me suis arrêté après plusieurs actions sans conclure. Redemande-moi la synthèse. »), jamais l'ancien placeholder.

SSE et persistance convergent sur la **même** valeur finale (le bug venait de leur divergence).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le tour produit un texte utile puis enchaîne des outils sans texte, puis un tour final vide | La réponse = le texte utile conservé (pas le vide) |
| Aucun bloc de texte de tout le tour (tous `tool_use`), tour final vide | Passe de synthèse → réponse ; jamais l'ancien placeholder |
| Texte du tour vide après strip (réduit au seul marqueur `fin-de-tour`) | Passe de synthèse (si aucun texte antérieur conservé) |
| Synthèse en échec / rien rendu | Message de dernier recours actionnable |
| Arrêt subi (interruption, budget temps, plafond conso, tronqué, contexte débordé) | Message dédié existant conservé — hors périmètre de la détection (clôture non normale) |
| Tour interrompu (pod recyclé, écran quitté) | Hors périmètre : ce n'est pas un tour « vide » |

---

## Critères d'acceptation

- [ ] Un tour qui émet un texte utilisateur **puis** enchaîne des itérations d'outils sans texte **conserve** le texte émis comme réponse finale (test d'intégration reproduisant « texte streamé → plomberie → tour final vide ») — la réponse finale = le texte streamé, pas le vide.
- [ ] Un tour dont **aucun** bloc n'a de texte (tous `tool_use`) **produit** une réponse via la passe de synthèse.
- [ ] Un tour au texte **vide après strip** (marqueur `fin-de-tour` seul) déclenche la synthèse.
- [ ] Le substring littéral « Je n'ai pas produit de réponse pour ce message. » n'est **plus** persisté ni renvoyé (absent du chemin de rendu/persistance de `runLoop`).
- [ ] La passe de synthèse est **unique** (pas de récursion / boucle) et ne relance **aucun** crochet bloquant (`END_OF_TURN` non appelé pendant la synthèse ; outils non offerts).
- [ ] Non-régression : F-125-01 (`stripTurnMetadata`), F-126 (`<<essentiel>>` non stripé), F-119/F-120 et les tests atelier/gouvernance existants restent verts.
- [ ] Isolation : aucun changement d'accès données ; `requireOwned(userId, workspaceId)` inchangé, tout reste sous `user_id` + `workspace_id` du tour.

---

## Périmètre

### Hors scope (explicite)

- La cause racine du rituel promotion/dette (SF-125-06, déjà mergée).
- Le tour **tué** par recyclage de pod / sortie d'écran (tour interrompu, pas vide).
- La discipline F-120 (résultat collé ≠ ordre) : renforcée ailleurs si besoin.
- `AtelierSessionService` (chemin Managed Agents, constante `EMPTY_REPLY` distincte) : cadrage §6 vise `AtelierChatService.runLoop` uniquement — voir drapeau.
- Aucune table, aucune migration, aucun endpoint, aucun composant frontend nouveau.

---

## Technique

### Composants impactés

- `backend/.../atelier/AtelierChatService.java` :
  - `runLoop` : mémorisation du dernier texte utilisateur non vide (après strip) ; résolution de la réponse à la clôture normale (conserver / synthèse / dernier-recours).
  - nouvelle méthode privée de synthèse forcée (un appel `agentProvider.nextTurn`, sans outils, sans crochet, tokens comptés dans les compteurs du tour) ；
  - suppression de l'écriture du placeholder ; nouvelle constante de dernier recours.
- Réutilisation de `stripTurnMetadata` (SF-125-01) pour la détection « vide après strip ».
- Coexiste avec `GovernanceEndOfTurnCheckpoint` (non bloquant depuis SF-125-06).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires / composant service (mock `StubAiAgentProvider`)

- [ ] Texte utile puis outils sans texte puis final vide → réponse = texte utile conservé.
- [ ] Tous `tool_use`, jamais de texte, final vide → synthèse relancée une fois → réponse = texte de synthèse.
- [ ] Texte final réduit au marqueur `fin-de-tour` seul, sans texte antérieur → synthèse déclenchée.
- [ ] Synthèse elle-même vide → message de dernier recours (pas le placeholder).
- [ ] Le substring « Je n'ai pas produit de réponse pour ce message. » n'est jamais persisté (capture `messageRepository.save`).
- [ ] Synthèse unique : un seul appel de synthèse, aucun crochet `END_OF_TURN` supplémentaire relancé.

### Tests d'intégration

- [ ] Reproduction du cas réel CAGIP au niveau boucle : texte streamé (via `onText`) + édition de carte + tour final vide → réponse finale conservée et SSE/persistance convergents.

### Isolation workspace

- [x] Non applicable directement (aucun nouvel accès données) — `requireOwned` et le filtrage `user_id`/`workspace_id` du tour restent inchangés ; couvert par les tests existants.

---

## Dépendances

### Subfeatures bloquantes

- SF-125-06 (a/b) — statut : Done (cause racine retirée).
- SF-125-01 (`stripTurnMetadata`) — statut : Done (réutilisé).
- F-126 (`<<essentiel>>`) — statut : Done (non stripé).

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Préoccupations transversales

| Préoccupation | Impactée ? | Composants |
|--------------|-----------|-----------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | `runLoop` conserve `requireOwned(userId, workspaceId)` ; aucun nouveau résolveur de tenant |
| Plans / limites | Non (tokens de synthèse comptés dans les compteurs existants du tour, décomptés via `recordUsage`) | `runLoop` compteurs `inputTokens`/`outputTokens`/cache |
| Navigation / routing | Non | — |

---

## Notes et décisions

- **Drapeau 1** : la synthèse est relancée **sans outils** (liste vide) pour garantir « pas de plomberie » et l'absence de tout crochet bloquant — plutôt que de repasser par la boucle. Écart mineur vs cadrage §4.3 (qui n'interdit pas les outils) mais fidèle à l'esprit « pas de plomberie » et à la borne anti-boucle.
- **Drapeau 2** : `AtelierSessionService.EMPTY_REPLY` (chemin Managed Agents) conserve son placeholder — hors périmètre §6 (le cas réel et la boucle vivante passent par `AtelierChatService`). À traiter séparément si le chemin Managed Agents redevient actif.
- **Drapeau 3** : la garde anti-bloc-vide envoyée **au modèle** lors d'un rejeu de crochet `END_OF_TURN` (l'API refuse un bloc de texte vide) utilise désormais un marqueur interne neutre, jamais persisté ni affiché — la phrase-placeholder disparaît totalement du code.
