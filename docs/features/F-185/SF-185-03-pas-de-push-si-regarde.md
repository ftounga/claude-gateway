# Mini-spec — [F-185 / SF-185-03] D7 enfin tenue : pas de push si le terminal est regardé

## Identifiant

`F-185 / SF-185-03`

## Feature parente

`F-185` — Rien ne vous attend en silence

## Statut

`in-progress`

## Date de création

2026-10-10

## Branche Git

`feat/SF-185-03-pas-de-push-si-regarde`

---

## Objectif

Ne pas faire vibrer le téléphone pour un terminal que l'on est en train de regarder, et ne jamais envoyer deux fois la même notification à la suite (engagement D7 de F-153, jamais codé).

---

## Comportement attendu

### Cas nominal

1. **« Regardé »**, côté écran : l'onglet est **visible** (`document.visibilityState === 'visible'`), il a le **focus** (`document.hasFocus()`), et l'utilisateur a **interagi** (pointeur, clavier, défilement, toucher) depuis moins de **2 minutes**. Un écran resté allumé devant une chaise vide n'est pas regardé.
2. Le **battement de cœur existant** du terminal (`POST /api/workspaces/{id}/terminal/live`, toutes les 30 s, F-70) transporte `watched: true|false`. Un battement supplémentaire part immédiatement :
   - quand l'onglet est caché ou perd le focus (`watched: false`) : les notifications reprennent tout de suite ;
   - quand il redevient visible et prend le focus (`watched: true`).
3. **Côté serveur**, la colonne `live_terminals.watched_at` reçoit :
   - `now` si `watched` vaut `true` ;
   - `null` si `watched` vaut `false` ;
   - rien n'est modifié si le champ est absent (écran antérieur).
4. **L'émetteur** (`PushNotificationService.deliver`) n'envoie rien si une place de ce compte, sur **ce** terminal, a un `watched_at` de moins de **45 s** (30 s de battement plus une marge).
5. **Anti-doublon** : un même événement pour un même compte et un même terminal n'est pas renvoyé dans les **30 s**. La mémoire est locale au pod, ce qui suffit : un tour vit sur un seul pod.
6. Le signal dans l'onglet (préfixe de titre et favicon, SF-153-01) reste inchangé.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Écran antérieur (pas de champ `watched`) | `watched_at` inchangé : la notification part comme avant | 200 |
| Lecture de présence en échec (base) | La notification part (mieux vaut un doublon qu'un silence) | — |
| Terminal d'un autre compte | Aucune incidence : la présence est lue par `user_id` **et** `workspace_id` | — |
| Onglet fermé brutalement | `watched_at` vieillit, et les notifications reprennent au plus tard 45 s après | — |
| Projet non possédé | 404, rien n'est écrit (inchangé) | 404 |

---

## Critères d'acceptation

- [ ] Un battement `watched: true` pose `watched_at`, `false` l'efface, et une absence de champ ne change rien.
- [ ] Une notification n'est pas émise si le terminal est regardé depuis moins de 45 s ; elle l'est au-delà.
- [ ] La présence d'un **autre** compte, ou d'un **autre** terminal du même compte, n'empêche rien.
- [ ] Le même événement pour le même terminal n'est pas émis deux fois en 30 s ; un événement différent l'est.
- [ ] Côté écran : `watched` est vrai seulement si l'onglet est visible, a le focus et a reçu une interaction récente ; passer en arrière-plan envoie aussitôt un battement `false`.
- [ ] Migration Liquibase réversible.

---

## Plan de test

- **Unitaires backend** :
  - `PushNotificationServiceTest` : regardé → rien ; présence périmée → envoi ; échec de lecture → envoi ; doublon en 30 s → un seul envoi ; deux événements différents → deux envois ;
  - `LiveTerminalServiceTest` : le drapeau est transmis.
- **Intégration** (`LiveTerminalApiIntegrationTest` ou équivalent) :
  - `watched: true` → `watched_at` posé ; `false` → `null` ; absent → inchangé ;
  - requête de présence filtrée par `user_id` (un autre compte sur le même `workspace_id` ne compte pas).
- **Frontend** (`live-terminal.service.spec.ts`) : `watched` vaut vrai seulement si l'onglet est visible, a le focus et a reçu une interaction récente ; `visibilitychange` vers caché → battement immédiat `false`.
- **Isolation utilisateur** : test d'intégration ci-dessus.

---

## Composants impactés

- Migration `149-live-terminals-watched.xml` : colonne `watched_at timestamptz null` sur `live_terminals`, rollback = suppression de la colonne.
- `terminals/LiveTerminal` (champ), `LiveTerminalRepository` :
  - `markWatched(userId, sessionId, at)` ;
  - `existsByUserIdAndWorkspaceIdAndWatchedAtAfter`.
- `LiveTerminalService.claim(..., Boolean watched)` et `watching(userId, workspaceId)`.
- `LiveTerminalClaimRequest.watched`, `LiveTerminalController`.
- `push/PushNotificationService` : présence, via l'interface `TerminalWatch` pour ne pas coupler `push` à `terminals`, et anti-doublon.
- Frontend : `core/services/live-terminal.service.ts`, qui calcule `watched` et réagit à `visibilitychange`, `focus`, `blur` et aux interactions.
- Endpoint modifié : `POST /api/workspaces/{id}/terminal/live` (champ facultatif ajouté, rétrocompatible).

## Préoccupations transversales

- **Contexte tenant** : oui. Composants qui résolvent le tenant :
  - `LiveTerminalService.claim`, qui vérifie la propriété du projet (`requireOwned`) avant toute écriture ;
  - `markWatched`, filtré par `user_id` et `session_id` ;
  - `watching`, filtré par `user_id` et `workspace_id` ;
  - `PushNotificationService.deliver`.

  Les quatre sont testés.
- Auth, plans / limites, navigation : non. Le plafond de 4 terminaux n'est pas touché : le drapeau est posé après la prise de place.

---

## Périmètre

### Hors scope (explicite)

- Centre de notifications : SF-185-04.
- Rappel avant expiration, qui ne sera **pas** filtré par la présence : SF-185-05.
- Préférences par appareil : SF-185-06.
