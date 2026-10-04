# Mini-spec — F-175 / SF-175-07 — Reprise de l'existant

## Identifiant
`F-175 / SF-175-07` — feature parente `F-175` *Le fil des attentes* — dépend de SF-175-01→06 (mergées).
Branche : `feat/SF-175-07-reprise-existant`. Statut : `in-progress`. Date : 2026-10-05.

## Objectif
Ne pas hériter d'une liste fausse : les attentes ouvertes nées sous F-154 (un seul état « ouvert »)
sont présentées **une fois** avec un état proposé, et l'utilisateur valide — rien ne change sans lui.

## Comportement attendu

### Cas nominal (D9)
1. **Migration 144** : `terminal_actions.review_pending` (booléen, défaut faux) ; vrai pour les
   attentes `A_FAIRE` présentes au déploiement (les ~20 de prod). Toute attente née ensuite : faux.
2. **État proposé** : « Demandé » si la description commence par *Demander / Relancer / Transférer*
   (accents et casse ignorés), « À faire » sinon — la règle du cadrage.
3. `GET /api/terminal-actions/review` → `[{ action (avec le nom du terminal), suggestedStatus }]` :
   les attentes à vérifier **ouvertes** du compte, tous terminaux.
4. `POST /api/terminal-actions/review` `{ decisions: [{ id, status | null }] }` → `{ applied, ignored }` :
   chaque décision applique l'état retenu (par le service des attentes) et sort l'attente de la
   reprise ; `null` = garder l'état actuel. Un identifiant inconnu, d'autrui ou déjà vérifié est
   **ignoré** (compté), jamais une erreur.
5. Tout geste de l'utilisateur sur une attente (changement d'état, confirmation) vaut vérification.
6. **Écran** : à l'ouverture d'un terminal (hors lecture seule), s'il reste des attentes à vérifier,
   le panneau s'ouvre **une fois par session** sur la section « Reprise : N attentes à vérifier » —
   une ligne par attente (terminal d'origine, âge, bascule À faire / Demandé / Fait / Annulé
   pré-réglée sur l'état proposé), [Valider] par ligne, [Tout valider], [Plus tard]. La bande dit
   « N à vérifier » tant qu'il en reste.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Décision sur l'attente d'autrui / inconnue / déjà vérifiée | ignorée, comptée dans `ignored` |
| Reprise illisible | section absente (le tableau reste) |
| Enregistrement en échec | snackbar, lignes conservées |
| `sessionStorage` indisponible | la reprise ne se rouvre pas d'elle-même (la bande la signale) |

## Critères d'acceptation
- [ ] Migration 144 additive (H2 + PostgreSQL), ne marque que les `A_FAIRE` existantes.
- [ ] La liste propose « Demandé » pour Demander / Relancer / Transférer ; rien n'est modifié à la lecture.
- [ ] Seules les décisions envoyées changent quelque chose ; les autres attentes restent intactes.
- [ ] **ISOLATION** : Bob ne voit rien et ses décisions sur les attentes d'Alice sont ignorées.
- [ ] Le panneau s'ouvre une fois par session ; « Plus tard » referme sans rien changer.

## Plan de test
- **Backend** : `TerminalActionReviewServiceTest` (proposition) ; intégration
  `reviewProposesAndAppliesOnlyDecisions` (proposition, rien sans validation, Bob 0/ignoré, décisions
  partielles, attente née après le déploiement ignorée, états finaux) ; migration jouée sur
  PostgreSQL réel (Testcontainers).
- **Front** : ouverture une fois par session + « à vérifier » dans la bande ; section, choix,
  validation par ligne et en bloc, « Plus tard ».

## Impacts
Table `terminal_actions` (+ `review_pending`, migration `144-terminal-actions-review.xml`) ;
backend `TerminalActionReviewService` (nouveau), repository, service (gestes = vérification),
`TerminalActionsController` (2 routes) ; front modèles, service, panneau, bande, terminal.

### Préoccupations transversales
Contexte tenant : lecture / écriture transverses aux terminaux — composants :
`TerminalActionReviewService` (`findByIdAndUserId`, liste sous `user_id`) ; les changements d'état
passent par `TerminalActionService.changeStatus` (`requireOwned` du terminal de l'attente).
Navigation : non (panneau = état d'écran).

## Hors périmètre
Une heuristique plus fine (lecture du fil pour savoir si le message est parti) : la validation
humaine tranche.

## Arbitrages (réversibles)
- « Demander » est proposé « Demandé » comme le dit le cadrage, même s'il peut s'agir d'une demande
  à faire : l'utilisateur corrige d'un clic, et rien ne s'applique sans lui.
- Ouverture automatique une fois **par session de navigateur**, pas une fois pour toutes : tant
  qu'il reste à vérifier, on le redit au retour (la bande le dit en permanence).
