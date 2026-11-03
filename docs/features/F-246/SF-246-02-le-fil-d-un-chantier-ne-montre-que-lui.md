# Mini-spec — F-246 / SF-246-02 Le fil d'un chantier ne montre que lui

## Identifiant
`F-246 / SF-246-02`

## Feature parente
`F-246` — Les volets restent où on les met (cadrage `CADRAGE-F-246-les-volets-restent-ou-on-les-met.md`, cause 8)

## Statut
`in-review` · **Estimation :** ½ j · **Branche :** `feat/SF-246-02-passe-commun-replie` · **Date :** 2026-11-03 · **Dépend de :** SF-246-01, SF-221-01 (fil par chantier)

## Objectif
Le volet d'un chantier n'affiche d'emblée que les messages de ce chantier : le passé commun qu'il relit (messages d'autres fils d'avant son ouverture) est replié derrière « Contexte d'avant le chantier N (k messages) ».

## Constat
`GET /chat?chantier=N` rend ce que voit le fil N (`ChantierThread.visibleTo`) : le fil commun **et tout message antérieur à l'ouverture de N, quel qu'en soit le fil**. En prod (03/11), le volet du chantier 10, ouvert à 18:42, affichait d'abord ~94 messages du chantier 9 puis ses 2 messages : à l'œil, « le fil du 9 dans le volet du 10 ». La gateway rend déjà le fil de chaque message (`AtelierMessageResponse.chantier`, SF-221-01) ; l'écran l'ignorait.

## Comportement attendu

### Cas nominal
1. `AtelierMessage.chantier` (déjà servi) est lu ; `toThreadItem` le reporte sur l'élément du fil (absent pour un message optimiste pas encore relu).
2. Dans un terminal à fil de chantier (volet de gauche épinglé ou volet de droite), les messages **de tête** dont le fil n'est pas ce chantier (autre chantier ou fil commun) sont repliés : bouton « Contexte d'avant le chantier N (k messages) », replié par défaut, ré-affichable et repliable (mécanique « Voir l'historique » de F-117, réutilisée).
3. Si un repli « nouveau départ » (F-117) plus long existe, le plus long l'emporte et le libellé reste « Voir l'historique (k messages) ».
4. Rien ne change côté serveur ni dans ce que relit le modèle (préfixe et rejeu inchangés).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Message sans `chantier` (backend antérieur, message optimiste) | jamais replié | — |
| Le chantier n'a encore aucun message à lui | tout le passé commun est replié ; le bouton reste | — |
| Fil commun (pas de chantier) | inchangé, rien de replié | — |
| Sujet d'autrui | inchangé : `GET /chat` → 404 (`requireOwned`) | 404 |

## Critères d'acceptation
- [x] Volet du chantier 10 : les messages du chantier 9 d'avant l'ouverture sont repliés ; ceux du 10 visibles.
- [x] « Contexte d'avant le chantier 10 (k messages) » révèle puis replie.
- [x] Un message du fil commun **après** le premier message du chantier reste visible (il fait partie du fil).
- [x] Hors chantier : aucun changement.

## Plan de test
- Unitaires : `thread-item.spec.ts` (+1 : `chantier` reporté, absent si non servi) ; `terminal-passe-commun-f246.spec.ts` (repli de tête, compte, libellé, révéler/replier, priorité F-117, fil commun inchangé, message optimiste jamais replié).
- Banc headless : historique du chantier 10 avec le passé du 9 → seul le fil du 10 est visible, à 1366×768 et 360 px.
- Isolation : aucune lecture nouvelle.

## Impact
Frontend seulement : `core/models/atelier.models.ts` (`chantier?`), `atelier/atelier.types.ts`, `atelier/thread-item.ts`, `terminal/atelier-terminal.component.{ts,html}`. Aucune table, aucun endpoint, aucune migration. Préoccupations transversales : aucune.

## Hors périmètre
Modifier ce que relit le modèle ; masquer les messages du fil commun postérieurs à l'ouverture.
