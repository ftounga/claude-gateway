# Cadrage F-198 — Changer de sujet sans rafraîchir la page

**Demande PO (2026-10-15)**, livraison autonome (vague). Constat vérifié sur `origin/main` 6e16f2c8 et en prod. Justesse avant coût ; charte `DESIGN_SYSTEM.md` (aucune couleur nouvelle) ; préfixe système stable (rien de volatil).

## Besoin
En passant d'un sujet/terminal à un autre, le contenu affiché n'est pas le dernier : il faut rafraîchir la page. « Très saoulant », gros problème d'usage.

## Cause (déduite du code)
`/atelier/A` → `/atelier/B` réutilise `AtelierComponent` ; `paramMap` → `followRouteWorkspace` (`atelier.component.ts:845`) → `selectWorkspace` (l.1467-1499) recharge bien, mais :
1. **L'état du tour n'est pas remis à zéro** : `submitting`, `execStreaming`, `streaming`, `interrupting`, `cardsOfTurn`, chrono, question/autorisation en attente restent ceux de A. `reattachTurn(B)` incrémente la génération et écarte les événements de A, y compris son `done` (seul chemin vers `endTurnDisplay`) ; le `onIdle` de B ne fait que `turnHeard = true` (l.3773-3779). B garde la ligne vivante/spinner de A et ne recharge jamais son fil en fin de tour. Le flux `streamChat` de A n'est pas annulable (l.2055).
2. **Réponses HTTP en course** : `loadHistory` (l.1501), `refreshTree`/`getWorkspace` (l.1624-1635), `loadResumeState` (l.1515) écrivent sans vérifier `activeWorkspaceId() === id` ⇒ le fil/détail de A peut s'afficher dans B.
3. `historyRevealed` du terminal non réinitialisé au changement de `projectId` (setter l.340-358).

## Décisions
- `selectWorkspace` remet à zéro tout l'état de tour avant `reattachTurn` ; flux de A annulé (AbortController) ou ignoré proprement.
- Toute réponse asynchrone vérifie le projet affiché avant d'écrire.
- Au retour sur un sujet, le fil est rechargé depuis le serveur (historique + rattachement au tour vivant s'il y en a un) : l'affichage = l'état serveur, sans F5.
- Tests : A en tour → B au repos ⇒ pas de spinner, fil de B ; réponses dans le désordre ⇒ B intact ; retour sur A ⇒ tour vivant de A rattaché.

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-198-01 | Remise à zéro de l'état de tour + gardes « projet affiché » sur les réponses + `historyRevealed` | 🟠 |
| SF-198-02 | Retour sur un sujet = rechargement fiable (historique + tour vivant), test de bout en bout du va-et-vient A↔B pendant un tour | 🟠 |
