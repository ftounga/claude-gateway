# Mini-spec — F-194 / SF-194-01 Le jeton du poste se prolonge tant que le poste vit

## Identifiant

`F-194 / SF-194-01`

## Feature parente

`F-194` — Un poste actif ne perd jamais sa connexion

## Statut

`in-progress`

## Date de création

2026-10-12

## Branche Git

`feat/SF-194-01-jeton-prolonge-par-activite`

---

## Objectif

Un jeton runner encore valide est repoussé de `app.runner.token-ttl` (30 jours) à chaque signe de vie du poste, pour qu'un poste utilisé ne doive plus être réappairé tous les 30 jours.

## Contexte (constat du 2026-10-12)

Les trois postes du PO (EDENRED, CAGIP, FREE), appairés le 12/09, ont tous expiré le 12/10 : l'échéance était **fixe** (`expires_at = création + 30 j`, colonne non modifiable). La gateway a refusé le jeton à la reconnexion, le runner l'a effacé (comportement voulu, `RunnerMain` sur `AuthRejectedException`) et a exigé un nouveau `--code`.

## Comportement attendu

### Cas nominal

- `RunnerHeartbeatService.touch(tokenId)` est déjà appelé à l'ouverture du canal WebSocket, à chaque heartbeat et à chaque poll (repli long-polling). En plus de `last_seen_at = now`, il pose `expires_at = now + token-ttl`, **uniquement si le jeton est valide à cet instant** (ni révoqué, ni expiré).
- Même écriture que `last_seen_at` (même entité, même transaction) : aucune requête supplémentaire.
- Un poste inactif plus de 30 jours expire comme aujourd'hui ; il faut alors un nouveau code.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Jeton révoqué | `last_seen_at` mis à jour comme avant ; `expires_at` **inchangé** (une révocation n'est jamais annulée) |
| Jeton déjà expiré | `expires_at` **inchangé** : on ne ressuscite pas un jeton mort |
| Jeton inconnu | No-op, comme avant |

## Critères d'acceptation

- [ ] Après `touch`, un jeton valide a `expires_at ≈ now + ttl` (et plus tard que l'échéance initiale quand le temps a passé).
- [ ] Un jeton révoqué garde son `expires_at`.
- [ ] Un jeton expiré garde son `expires_at` et reste refusé par `RunnerTokenAuthenticator`.
- [ ] La durée vient de `app.runner.token-ttl` (`APP_RUNNER_TOKEN_TTL`), la même que celle de l'émission.
- [ ] Aucune modification du runner ni du frontend nécessaire ; l'écran « Expire le … » affiche désormais l'échéance glissante.

## Plan de test

- Unitaire/intégration (`RunnerHeartbeatServiceTest`, contexte Spring + H2) : prolongation d'un jeton valide ; jeton révoqué inchangé ; jeton expiré inchangé et toujours non authentifiable.
- Non-régression : suites `runner` existantes (`RunnerTokenServiceTest`, authentificateur, poll, WebSocket) + suite backend complète.
- Isolation utilisateur : inchangée — `touch` reçoit l'identifiant d'un jeton déjà authentifié par le handshake ; aucune nouvelle lecture inter-utilisateur.

## Impacts

- Table `runner_tokens` : colonne `expires_at` devient modifiable côté JPA (`updatable = false` retiré). **Aucune migration.**
- Composants : `RunnerToken`, `RunnerHeartbeatService`.
- Endpoints : aucun.
- Préoccupations transversales : Auth / Principal — **oui** (durée de vie d'un jeton runner). Composants impactés : `RunnerTokenAuthenticator` (lit `expires_at`, inchangé), `RunnerWebSocketHandler` et `RunnerPollController` (appellent `touch`, inchangés), `RunnerKillSwitchService` (lit `isValidAt`, inchangé), `RunnerTokenService.revoke` (inchangé). Contexte tenant, plans/limites, navigation : non.

## Sécurité

Un jeton volé resterait valable tant qu'il sert. C'était déjà le cas pendant 30 jours ; la révocation (écran Postes, coupe-circuit F-38) reste le remède et n'est pas affectée. Pas de durée de vie absolue ajoutée (hors scope, à cadrer si le besoin apparaît).

## Hors scope

- Rotation du jeton (nouveau clair émis par la gateway).
- Plafond de durée de vie absolu.
- Réparer les jetons déjà expirés ce matin : un nouveau code suffit.
