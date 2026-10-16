# Mini-spec — [F-199 / SF-199-02] Clic du Fil : navigation unifiée qui bascule le client courant

## Identifiant
`F-199 / SF-199-02` — Feature parente `F-199` — Statut `in-progress` — Créée 2026-10-16
Branche : `feat/SF-199-02-clic-du-fil` — dépend de SF-199-01 (client courant)

## Objectif
Ouvrir une tâche depuis la bande « Fil » (ligne ou bandeau de fin « Ouvrir ») emprunte un seul chemin : le client courant bascule sur le client de la tâche **avant** la navigation, puis l'adresse `/atelier/<id>` est suivie par l'écran (même chemin que la passation et « précédent »), qui remet l'état de tour à zéro (F-198) sans interrompre le tour en cours (F-84).

## Comportement nominal
1. La bande retient le poste servi par `GET /api/workspaces/{id}/fil` (`hostId`, `null` ⇒ Hébergé).
2. `openTask(t)` (ligne) et `openFinished(t)` (bandeau) passent par une seule méthode `goTo(t)` : client courant = poste de la bande (`heberge` si `null`), puis `router.navigate(['/atelier', t.workspaceId])`.
3. La ligne « ici » reste inerte (inchangé).
4. Côté écran, l'adresse qui change est suivie par `followRouteWorkspace` ⇒ `selectWorkspace` : **pas de garde de tour** — pendant un tour, la navigation est permise, le tour continue côté serveur, l'état d'écran est remis à zéro (F-198). Contrairement à `openHandoff` (passation, D4 F-179), qui reste refusée pendant un tour : la passation dépose une phrase dans la saisie, le Fil non.

## Cas d'erreur
- Réponse du Fil sans `hostId` lisible (première lecture pas encore arrivée) ⇒ le client courant n'est pas touché ; l'écran le recalcule à l'ouverture du terminal (SF-199-01).
- Terminal hors de portée (403/404) ⇒ bande vide, aucun clic possible (inchangé).

## Critères d'acceptation
- [ ] CA1 Clic sur une ligne d'un autre terminal ⇒ client courant = poste de la bande, puis navigation `/atelier/<id>`.
- [ ] CA2 « Ouvrir » du bandeau de fin ⇒ même effet (même méthode).
- [ ] CA3 Bande d'un terminal sans poste ⇒ client courant `heberge`.
- [ ] CA4 Pendant un tour sur A, l'adresse passe à B ⇒ B affiché, plus de spinner ni d'invite de A, aucun `interrupt`.
- [ ] CA5 Non-régression : specs `terminal-fil`, `atelier` (F-179, F-198) vertes.

## Plan de test
- `terminal-fil.component.spec.ts` : CA1, CA2, CA3.
- `atelier.component.spec.ts` (bloc URL) : CA4 via émission de `paramMap`.
- Isolation utilisateur : aucun endpoint nouveau ; le Fil reste scellé `user_id` + poste (`PosteTurnsService`).
- Non-régression : `npm run build && npm test`.

## Impacts
- Tables / endpoints : aucun (frontend seul).
- Composants : `TerminalFilComponent` (`open`, poste retenu), `AtelierComponent` (inchangé, testé).
- Préoccupations transversales :
  - [x] **Navigation / routing** — chemins vérifiés : clic du Fil, bandeau de fin, passation (`openHandoff`, garde inchangée), « Revenir » (`returnFromHandoff`), « précédent » (`followRouteWorkspace`).
  - [x] **Contexte client** — `CurrentClientService` écrit par `TerminalFilComponent` (nouveau), `AtelierComponent` et `PostesComponent` (SF-199-01).
- Design system : rien de visuel.

## Arbitrage tracé — « client affiché sur les tâches d'un autre client »
La mesure en prod (SF-199-01) établit que la bande est **mono-poste** (`PosteTurnsService` : `findByUserIdAndHostId` + défense en profondeur) : une tâche d'un autre client n'y apparaît jamais. Une étiquette « client » par ligne serait du code mort, jamais exercé par les données réelles. **Non implémentée** ; à reprendre si la portée du Fil s'élargit un jour à plusieurs postes (il faudrait alors porter `hostId`/`hostName` par tâche côté backend). Réversible.

## Hors périmètre
- Élargir la portée du Fil à plusieurs postes.
- Toute modification backend.
