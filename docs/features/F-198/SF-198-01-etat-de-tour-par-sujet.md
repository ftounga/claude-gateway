# Mini-spec — [F-198 / SF-198-01] L'état de tour appartient au sujet affiché

## Identifiant
`F-198 / SF-198-01` — Feature parente `F-198` — Statut `in-progress` — Créée 2026-10-16
Branche : `feat/SF-198-01-etat-tour-par-sujet`

## Objectif
Passer du sujet A au sujet B (`/atelier/A` → `/atelier/B`, même composant réutilisé) affiche l'état de B et lui seul : aucun spinner, chrono, ligne vivante, invite ni fil hérités de A, aucune réponse tardive de A écrite dans B.

## Comportement nominal
1. `selectWorkspace(B)` remet à zéro **tout l'état de tour** avant de recharger B :
   `submitting`, `interrupting`, `replaying` ← faux ; `endTurnDisplay()` (chrono, `streaming`, `execStreaming`,
   autorisation et question en attente) ; `cardsOfTurn` vidé ; précisions différées de A abandonnées ;
   invite de plan (`planAwaitingApproval`, `carriedPlan`), marqueur de compaction et bilan de A retirés.
2. **Époque d'affichage** (`displayEpoch`) incrémentée à chaque changement de sujet. Les flux d'émission
   lancés depuis l'écran (`streamChat` boucle maison, `streamAgent` sandbox) sont enveloppés : tout rappel
   arrivé après un changement de sujet est **ignoré** (y compris `acceptSeq`, `onDone`, `onError`,
   `onClosed`, `onIdle`). Le tour de A **continue côté serveur** : on n'abandonne pas sa requête.
3. **Gardes « projet affiché »** : `loadHistory`, `loadResumeState`, `refreshTree` n'écrivent (succès comme
   erreur) que si `activeWorkspaceId() === id` au retour de la réponse.
4. Terminal : `historyRevealed` (F-117) revient à replié quand `projectId` change.

## Cas d'erreur
- Réponses HTTP de A arrivant après le passage à B (dans le désordre) ⇒ ignorées, pas de snackbar d'erreur de A dans B.
- `done`/`error` du flux de A arrivant pendant B ⇒ ignorés : pas de réponse de A ajoutée au fil de B, pas de message optimiste retiré, pas d'erreur affichée.
- Envoi sur B pendant que A tourne encore ⇒ B démarre normalement (aucun `submitting` hérité ne le bloque).

## Critères d'acceptation
- [ ] CA1 A en tour (submitting, chrono, ligne vivante, autorisation en attente) → sélection de B ⇒ `submitting` faux, `execStreaming`/`streaming` nuls, aucune invite, chrono arrêté.
- [ ] CA2 `onDone` du flux de A appelé après le passage à B ⇒ fil de B inchangé.
- [ ] CA3 `onError` du flux de A après passage à B ⇒ aucune erreur affichée, fil de B inchangé.
- [ ] CA4 `getHistory(A)` répond après `getHistory(B)` ⇒ le fil affiché reste celui de B ; idem `getWorkspace`, `getResume`.
- [ ] CA5 Terminal : historique révélé sur A ⇒ replié en arrivant sur B.
- [ ] CA6 Non-régression : tous les specs existants de l'Atelier verts (F-84, F-131, F-179…).

## Plan de test
- `atelier.component.spec.ts` : bloc `changer de sujet (F-198 / SF-198-01)` — CA1 à CA4 (réponses dans le désordre via `Subject`).
- `atelier-terminal.component.spec.ts` : CA5.
- Isolation utilisateur : inchangée — aucun appel nouveau ; tous les endpoints restent scellés `user_id` côté backend.
- Non-régression : `npm run build && npm test` complet.

## Impacts
- Tables / endpoints : aucun (frontend seul).
- Composants : `AtelierComponent` (`selectWorkspace`, `loadHistory`, `loadResumeState`, `refreshTree`, envois), `AtelierTerminalComponent` (setter `projectId`).
- Préoccupations transversales : **Contexte tenant / projet affiché** cochée — composants qui résolvent le projet affiché : `AtelierComponent.activeWorkspaceId` (source unique), `followRouteWorkspace`, `selectWorkspace`, `reattachTurn`/`attachHandlers` (déjà gardés par génération), `armStreamProbe` (déjà gardé par id), `AtelierTerminalComponent.projectId`. Aucun changement d'auth, de plans ni de routes.
- Design system : rien de visuel.

## Hors périmètre
- Rechargement au retour sur un sujet et test de va-et-vient A↔B pendant un tour : SF-198-02.
- Annulation réseau du flux de A (`AbortController`) : arbitrage — **ignoré proprement** plutôt qu'abandonné, pour ne jamais risquer d'interrompre le tour de A côté serveur (le cadrage autorise les deux).
- Backend.
