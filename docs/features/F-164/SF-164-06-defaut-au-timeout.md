# Mini-spec — [F-164 / SF-164-06] Défaut au timeout

---

## Identifiant

`F-164 / SF-164-06`

## Feature parente

`F-164` — Questions structurées à l'utilisateur (parité Claude Code `AskUserQuestion`)

## Statut

`in-review`

## Date de création

2026-10-06

## Branche Git

`feat/SF-164-06-defaut-au-timeout`

---

## Objectif

Quand une question structurée (`demander`) expire sans réponse, retenir l'option recommandée de chaque question, le dire explicitement au modèle comme **décision par défaut à signaler**, et le montrer à l'écran (cadrage F-164 §3 « Décider-par-défaut si non-attendu »).

---

## Comportement attendu

### Cas nominal (TIMEOUT)

1. Le délai de la question expire (`AnswerOutcome.Status.TIMEOUT`).
2. Pour chaque question du lot : si une option est `recommended`, elle est retenue ; sinon la question est signalée **sans réponse**.
3. L'outil rend au modèle un `ToolOutcome.info` (pas une erreur) qui dit :
   - que l'utilisateur **n'a pas répondu** ;
   - la liste « décidé par défaut, faute de réponse : intitulé → option » (et les questions sans recommandée) ;
   - qu'il doit **signaler** ces défauts dans sa réponse pour que l'utilisateur puisse corriger ;
   - **garde-fou F-167** : si un défaut porte sur une action irréversible ou sensible (MR/PR, déploiement prod, suppression, envoi externe, dépense, sécurité/permissions), **ne pas l'exécuter** sur ce défaut — s'arrêter et signaler que la décision attend l'utilisateur ; une question sans recommandée → décider selon la doctrine (petit et réversible) ou laisser en suspens.
4. L'événement `question_resolved` (statut `timeout`) porte un champ **additif** `defaults` : liste de lignes lisibles « intitulé : option » / « intitulé : sans réponse ». Absent/vide pour les autres statuts.
5. La carte de question expirée affiche « Délai écoulé — décidé par défaut » et la liste des choix retenus ; le message éphémère dit que le tour a repris sur l'option recommandée.

### Cas d'erreur / variantes

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucune question du lot n'a d'option recommandée | `info` : utilisateur absent, aucune décision par défaut possible, toutes signalées sans réponse ; `defaults` liste les « sans réponse » |
| INTERRUPTED | Inchangé : `ToolOutcome.error("Le tour a été interrompu avant la réponse.")`, aucun défaut, pas de `defaults` |
| Réponse reçue (ANSWERED) | Inchangé |
| Autre statut (FAILED) | Inchangé (erreur) |
| Backend antérieur (pas de `defaults`) | Front : carte « Délai écoulé » comme avant (rétro-compatible) |

---

## Critères d'acceptation

- [ ] CA1 — TIMEOUT : le modèle reçoit un résultat **non-erreur** contenant « n'a pas répondu », « décidé par défaut, faute de réponse » et l'option recommandée de chaque question.
- [ ] CA2 — Question sans option recommandée : signalée « sans réponse » (aucune option inventée).
- [ ] CA3 — Le résultat porte le garde-fou irréversible/sensible (« ne l'exécute pas sur ce défaut »), cohérent avec `DECIDE_BY_DEFAULT_DOCTRINE`.
- [ ] CA4 — INTERRUPTED inchangé (erreur, aucun défaut).
- [ ] CA5 — `question_resolved` porte `defaults` au timeout ; ne le porte pas (ou vide) sinon ; les deux émetteurs (SSE controller, lanceur MCP) le relaient.
- [ ] CA6 — Front : le service mappe `defaults` (tableau de chaînes, filtré) ; la carte expirée affiche « décidé par défaut » + les lignes ; sans `defaults`, rendu inchangé.
- [ ] CA7 — Aucune consigne variable ajoutée au bloc système (préfixe stable F-171) : le texte vit dans le résultat d'outil.
- [ ] CA8 — Design system : uniquement les classes/jetons `--cg-*` existants de la carte.

---

## Périmètre

### Hors scope (explicite)

- Mode « vague autonome » sans attente (décider sans poser la question) — couvert par la doctrine F-167.
- Détection automatique du caractère irréversible d'une option (le modèle juge, guidé par la consigne).
- Persistance/historique des défauts au-delà du flux du tour.
- Vue mosaïque (`live-turn-view`) : elle retire la question à la résolution, inchangé.

---

## Valeurs initiales

Sans objet.

---

## Contraintes de validation

Sans objet (aucune entrée utilisateur nouvelle ; `defaults` est produit par le serveur à partir du lot déjà validé, bornes `AtelierQuestionForm` inchangées).

---

## Technique

### Endpoint(s)

Aucun nouveau. Contrat SSE `question_resolved` : champ additif `defaults: string[]` (présent seulement au timeout).

### Tables impactées

Aucune. Pas de migration.

### Composants backend

- `AtelierQuestionForm` — `defaultDecisions()` (option recommandée par question, ou rien).
- `AtelierProgressListener.AtelierQuestionResolved` — composant `defaults`.
- `AtelierChatService.applyQuestion/askQuestion` — TIMEOUT → `info` + `defaults`.
- `AtelierChatController.StreamQuestionResolved`, `AtelierMcpTurnLauncher` — relais de `defaults`.

### Composants Angular

- `atelier.models.ts` (`AtelierQuestionResolved.defaults?`, `AtelierPendingQuestion.defaults?`), `atelier.service.ts` (mapping), `atelier.component.ts` (`clearQuestion`), `atelier-terminal-demande.component.html/.scss`.

### Préoccupations transversales

Aucune cochée (ni auth, ni tenant, ni plans/limites, ni routing).

---

## Plan de test

### Tests unitaires

- `AtelierChatServiceQuestionToolTest` : timeout → info avec recommandées + garde-fou ; question sans recommandée ; `question_resolved.defaults` relayé ; interruption inchangée.
- `AtelierQuestionFormTest` (ou équivalent) : `defaultDecisions()`.
- Front : `atelier.service.spec` (mapping `defaults`), spec de la carte (rendu « décidé par défaut »).

### Tests d'intégration

Suite backend complète + `npm test`.

### Isolation workspace

Inchangée : la résolution est émise dans le tour du propriétaire (`userId`/`workspaceId` de la porte).

---

## Dépendances

SF-164-01/02 (outil + carte, livrées), F-167 (doctrine décider-par-défaut, livrée).

### Questions ouvertes impactées

Aucune.
