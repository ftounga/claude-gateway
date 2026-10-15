# Mini-spec — [F-198 / SF-198-02] Retour sur un sujet = rechargement fiable

## Identifiant
`F-198 / SF-198-02` — Feature parente `F-198` — Statut `in-progress` — Créée 2026-10-16
Branche : `feat/SF-198-02-retour-sujet-recharge` — dépend de SF-198-01 (mergée, #1182)

## Objectif
Revenir sur un sujet affiche l'état serveur — fil complet et tour vivant rattaché s'il y en a un — sans rafraîchir la page, même si un tour s'est achevé pendant l'absence.

## Comportement nominal
1. À l'ouverture d'un sujet (`selectWorkspace`) : historique, reprise, arborescence lus (inchangé) + rebranchement `attachTurn(id, 0)` (inchangé).
2. Le rebranchement est enveloppé par la garde « sujet affiché » de SF-198-01 : un rebranchement abandonné (sujet quitté) ne touche plus l'écran, y compris l'`idle` émis par son abandon.
3. **Tour vivant** (`attached`) : l'écran se remet en tour ; sa fin (`done`) relit le fil (inchangé, F-84).
4. **Rien ne tourne** (`idle`) : le fil et l'arborescence sont **relus une fois** — l'historique lu à l'ouverture a pu partir avant la fin d'un tour achevé pendant l'absence (course lecture ↔ rebranchement).

## Cas d'erreur
- `idle` répété (suivi par fenêtres) ⇒ une seule relecture par ouverture.
- `idle` d'un rebranchement abandonné (sujet quitté, ou envoi qui l'a remplacé) ⇒ aucune relecture.
- Échec de la relecture ⇒ même message que le chargement initial, uniquement si le sujet est toujours affiché.

## Critères d'acceptation
- [ ] CA1 Va-et-vient A↔B pendant un tour de A : sur B, aucun état de A ; retour sur A ⇒ `attachTurn('A', 0)`, écran en tour (`submitting`), et la fin du tour relit le fil de A.
- [ ] CA2 Retour sur A dont le tour s'est fini pendant l'absence (`idle`) ⇒ `getHistory('A')` relu une fois de plus, le fil affiché est le plus récent.
- [ ] CA3 `idle` répété ⇒ pas de seconde relecture.
- [ ] CA4 `idle` du rebranchement de A arrivé après le passage à B ⇒ ni relecture de A, ni écriture dans B.
- [ ] CA5 Non-régression : specs F-84 / F-131 / F-179 verts.

## Plan de test
- `atelier.component.spec.ts`, bloc `revenir sur un sujet (F-198 / SF-198-02)` : CA1 à CA4.
- Isolation utilisateur : aucun endpoint nouveau ; `GET .../chat`, `GET /workspaces/{id}`, `.../chat/attach` restent scellés `user_id` côté backend.
- Non-régression : `npm run build && npm test` complet.

## Impacts
- Tables / endpoints : aucun (frontend seul ; une lecture d'historique supplémentaire par ouverture d'un sujet au repos).
- Composants : `AtelierComponent` (`selectWorkspace`, `reattachTurn`, `attachHandlers.onIdle/onAttached`).
- Préoccupations transversales : **Contexte projet affiché** — mêmes composants que SF-198-01 (`activeWorkspaceId`, `selectWorkspace`, `reattachTurn`/`attachHandlers`, `armStreamProbe`). Ni auth, ni plans, ni routes.
- Design system : rien de visuel.

## Hors périmètre
- Backend (pas de nouvel endpoint ni de changement d'événement).
- Reprise du compteur de reprise (`getResume`) à l'`idle` : non relue (donnée de confort, non affectée par la course).
