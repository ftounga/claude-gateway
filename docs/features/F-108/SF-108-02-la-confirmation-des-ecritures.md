# Mini-spec — F-108 / SF-108-02 — La confirmation des écritures

> Base : `docs/features/F-108/CADRAGE-F-108-agir-dans-microsoft-365.md` §4.4, §4.5 et §6.
> Cadrage validé par le PO : cette mini-spec l'applique.

## Identifiant

`F-108 / SF-108-02`

## Feature parente

`F-108` — Agir dans Microsoft 365

## Statut

`done` — PR #491

## Date de création

2026-09-13

## Branche Git

`feat/SF-108-02-confirmation-des-ecritures`

---

## Objectif

Classer chaque outil Teams en **lecture** ou **écriture**, et soumettre **chaque écriture** à
l'autorisation du terminal (charte §12) — action et emplacement nommés en clair —, tandis que les
lectures et téléchargements ne demandent rien ; le coupe-circuit arrête aussi les gestes en attente.

---

## Comportement attendu

### Cas nominal

1. `TeamsToolCatalog` porte la **classification** : les outils d'écriture (`teams_create_folder`,
   `teams_upload_file`, `teams_rename`, `teams_move`, `teams_delete`, `teams_replace_version`) et un
   prédicat `isWrite(tool)`. Tout autre `teams_*` est une **lecture**.
2. Avant d'émettre un outil d'écriture vers le runner, la boucle (`AtelierChatService`) demande
   l'autorisation par le **mécanisme existant du terminal** (`RunnerConfirmationGate`, F-38/SF-38-08),
   avec un libellé **en clair** nommant l'action et l'emplacement (« Créer le dossier « Livrables »
   dans Équipe Projet IAM › Général › Fichiers »).
3. **Chaque écriture** est confirmée : les écritures Teams **ne sont pas couvertes** par « Tout
   autoriser pour ce message » (§4.4 « chaque écriture »). Elles ne dépendent pas non plus du
   réglage `agent_ask_before_bash` (qui ne concerne que `bash`).
4. Une écriture **autorisée** est émise ; **refusée** (ou silence), elle n'est **jamais** émise, et
   le modèle reçoit le motif. Le journal d'audit runner trace l'appel (autorisé) ou le refus.
5. Les **lectures** et **téléchargements** ne passent par aucune demande.
6. **Coupe-circuit** (SF-38-08) : une écriture en attente d'autorisation est **libérée en refus**
   quand le tour est interrompu / le poste coupé — rien ne part.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Écriture Teams refusée par l'utilisateur | Non émise ; tool_result = « refusée par l'utilisateur » ; audit `DENIED` |
| Écriture Teams sans réponse dans le délai | Non émise ; tool_result = délai ; audit `TIMEOUT` |
| Lecture Teams | Émise sans demande |
| Coupe-circuit pendant l'attente | Demande libérée en refus, rien n'est émis |

---

## Critères d'acceptation

- [ ] `TeamsToolCatalog.isWrite(tool)` reconnaît **exactement** les six outils d'écriture et rien
      d'autre (ni lecture, ni capture, ni présentation, ni `bash`, ni `null`).
- [ ] Une écriture Teams déclenche une demande d'autorisation dont le libellé nomme **l'action et
      l'emplacement en clair**.
- [ ] Une écriture Teams refusée **n'atteint jamais** le runner (vérifié par l'absence d'appel au
      `RunnerToolGateway`), et est tracée `DENIED`.
- [ ] Une écriture Teams est confirmée **même** sous « Tout autoriser pour ce message » (chaque
      écriture, §4.4).
- [ ] Une lecture Teams n'est **pas** tenue derrière une demande.
- [ ] Le coupe-circuit (`cancelWorkspace`) libère une écriture en attente **en refus** (test existant
      `RunnerConfirmationGateTest` étendu ou référencé).

---

## Périmètre

### Hors scope (explicite)

- L'**implémentation** des outils d'écriture côté runner (SF-108-04) et de lecture fichiers
  (SF-108-03) : cette SF pose la **classification** et la **confirmation**, pas les capacités.
- Poster un message / répondre / réagir (hors périmètre F-108).

---

## Technique

### Composants impactés (préoccupation transversale — Sécurité §7 et Plans/limites)

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `TeamsToolCatalog` (backend) | modifié | constantes d'écriture, `WRITE`, `isWrite`, `describeWrite` (libellé clair) |
| `AtelierChatService` (backend) | modifié | écritures Teams → confirmation (hors blanket, hors `agent_ask_before_bash`), libellé clair ; audit cible d'écriture |
| `TeamsToolCatalogTest`, `AtelierChatServiceRunnerGuardTest` | tests | classification + confirmation + refus |

- **Plans / limites** : les outils d'écriture, comme tous les `teams_*`, restent gardés par le droit
  Teams / Vigie dans `toolsFor` (préfixe `teams_`) — inchangé ; ils ne sont donnés à l'agent que par
  SF-108-03/04. La classification ici ne change pas la garde de droit.
- **Auth / tenant** : inchangé — l'identité Microsoft reste celle du navigateur ; côté gateway,
  isolation `user_id` déjà appliquée par `RunnerConfirmationGate` (le propriétaire du workspace).

### Migration Liquibase

- [ ] Non applicable — la trace réutilise l'audit runner existant.

---

## Plan de test

### Tests unitaires

- [ ] `TeamsToolCatalogTest` — `isWrite` reconnaît les six écritures, refuse lecture/capture/
      présentation/`bash`/`null`.
- [ ] `TeamsToolCatalogTest` — `describeWrite` produit un libellé clair (action + emplacement).

### Tests d'intégration (boucle)

- [ ] `AtelierChatServiceRunnerGuardTest` — écriture Teams refusée → jamais émise, audit `DENIED`.
- [ ] `AtelierChatServiceRunnerGuardTest` — écriture Teams demande une autorisation au libellé clair.
- [ ] `AtelierChatServiceRunnerGuardTest` — lecture Teams non tenue derrière une demande.
- [ ] `RunnerConfirmationGateTest` — coupe-circuit libère une écriture en attente en refus (existant).

### Isolation utilisateur

- Couverte par `RunnerConfirmationGate` (décision acceptée du seul propriétaire du workspace) —
  test d'isolation existant (`cannot resolve another workspace/user`).

---

## Notes et décisions

- **Décision (irréversible côté sécurité, appliquée telle quelle du cadrage §4.4)** : chaque écriture
  Teams est confirmée, **hors** « Tout autoriser pour ce message ». Ce n'est pas une commodité qui se
  règle : c'est la garde du cadrage. Tracé comme appliqué.
- **Décision (réversible)** : le libellé clair vit dans `TeamsToolCatalog.describeWrite` (source
  unique), `AtelierChatService` en extrait l'item et l'emplacement des paramètres d'appel.
