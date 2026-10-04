# Mini-spec — F-175 / SF-175-04 — La bande et le panneau

## Identifiant
`F-175 / SF-175-04` — feature parente `F-175` *Le fil des attentes* — frontend ; contrat importé de
**SF-175-01** (`GET …/actions/board`, `POST …/{id}/status`, `PATCH …/{id}`, `POST …/actions`,
`…/reopen`) et **SF-175-02** (`…/proposal/confirm|dismiss`), toutes mergées.
Branche : `feat/SF-175-04-bande-et-panneau`. Statut : `in-progress`. Date : 2026-10-05.

## Objectif
Qu'on ne puisse plus louper une attente : une **bande** au-dessus de la saisie, relue à chaque fin de
tour, et un **panneau** trois colonnes où l'on traite tout le poste.

## Comportement attendu

### Cas nominal (D6)
1. **Bande** (`app-terminal-attentes-band`), au-dessus de la zone de saisie (hors lecture seule), dès
   qu'il y a ≥ 1 attente ouverte sur le poste (ou le terminal hébergé) : « Attentes · 2 à faire ·
   4 demandées · la plus ancienne 9 j » + « N à confirmer » si l'agent a proposé des fermetures. Un clic
   ouvre le panneau. Absente à zéro.
2. **Relecture** du tableau : à l'ouverture du terminal, **à chaque fin de tour** (passage
   `submitting` vrai → faux), après chaque geste du panneau. La pastille de la barre et l'entrée du
   menu ⋯ mobile suivent le même compte (poste).
3. **Panneau** : trois colonnes *À faire · Demandé · Fait récemment* (fermées depuis 7 jours, la plus
   récente d'abord) ; filtre *Ce terminal / Tout le poste* (seulement s'il y a un poste) ; une
   attente née ailleurs porte « né dans « X » » et se traite sur la route de **son** terminal.
4. **Gestes** : À faire → [Demandé] [Fait] ; Demandé → [Fait] [À refaire] ; ouvertes → [Modifier]
   [Annuler] ; fermées → [Rétablir] ; fermeture proposée → encart « L'agent pense que c'est réglé :
   « … » » [Confirmer] [Pas encore] ; [Ajouter] (ce qu'il faut obtenir, qui).
5. **Mobile** (< 900 px) : panneau plein écran, colonnes empilées ; la bande reste au-dessus de la saisie.
6. Palette et polices du `DESIGN_SYSTEM.md` uniquement (jetons `--cg-*`), Angular Material
   (`mat-form-field` outline, `MatSnackBar`, `mat-button-toggle`).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Tableau illisible à l'ouverture du terminal | ni bande ni pastille (rien plutôt qu'un chiffre faux) |
| Tableau illisible dans le panneau | « Les attentes n'ont pas pu être chargées. » sans détail technique |
| Geste en échec | l'écran revient à l'état d'avant + snackbar |
| Description vide (ajout / édition) | bouton désactivé |

## Critères d'acceptation
- [ ] La bande apparaît dès une attente ouverte sur le poste, disparaît à zéro, ouvre le panneau.
- [ ] Le tableau est relu à chaque fin de tour.
- [ ] Trois colonnes, filtre terminal/poste, nom du terminal d'origine.
- [ ] Gestes d'état, ajout, édition, rétablir, confirmer / pas encore — chacun sur la route du
      terminal de l'attente.
- [ ] Échec = retour à l'état d'avant + message.
- [ ] Rendu mobile empilé.

## Plan de test (Karma, service **mocké**)
`terminal-actions.spec.ts` : bande absente à zéro / présente / clic ; relecture en fin de tour ;
échec de chargement ; résumé (`bandSummary`, `oldestLabel`, `pendingProposals`) ; « à confirmer » ;
trois colonnes ; filtre poste ; route du terminal d'origine ; retour arrière sur échec ;
confirmer / pas encore ; rétablir / ajouter / éditer ; échec de chargement du panneau ; âge.

## Impacts
Front : `terminal-attentes-band.component.ts` (nouveau), `terminal-actions-panel.component.ts`
(refonte), `atelier-terminal.component.{ts,html}` (tableau, relecture fin de tour, bande), spec.
Aucun backend.

### Préoccupations transversales
Navigation / routing : non (panneau = état d'écran, pas de route). Auth / tenant / plans : non.

## Hors périmètre
Cartes dans le fil (SF-175-05) ; relance pré-remplie et compteurs rail / mosaïque (SF-175-06) ;
reprise de l'existant (SF-175-07). Glisser-déposer entre colonnes : remplacé par des boutons
(arbitrage réversible — D6 autorise « glisser **ou** bouton »).
