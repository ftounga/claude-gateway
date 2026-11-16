# Mini-spec — [F-266 / SF-266-02] Écrans : « Tous mes postes » dans la Forge et sur la carte

## Identifiant
`F-266 / SF-266-02`

## Feature parente
`F-266` — Règles de tous mes postes (portée COMPTE)

## Statut
`done`

## Date de création
2026-11-16

## Branche Git
`feat/SF-266-02-ecrans-tous-mes-postes`

---

## Objectif
Le PO voit, recopie et retire ses règles « tous mes postes » sans SQL, depuis la Forge et la carte de proposition.

## Comportement attendu

### Cas nominal
- Forge, « Ce qui s'applique vraiment », sous « Règles du client » : bloc **Tous mes postes** (liste
  titre + extrait, [Retirer] avec confirmation) ; texte vide « Aucune règle sur tous vos postes ».
- Sous le `GOUVERNANCE.md` du poste : une ligne par titre (`hostRuleTitles`) avec [Valable sur tous mes
  postes] ; si une règle COMPTE porte déjà ce titre : « Déjà sur tous mes postes » + bouton
  [Mettre à jour] (recopie à nouveau).
- Carte de proposition : portée COMPTE → « Pour tous mes postes » ; REGLE POSTE/SUJET **appliquée** →
  bouton [Valable sur tous mes postes] → « Recopiée sur tous vos postes ».

### Cas d'erreur
| Situation | Résultat |
|---|---|
| Poste injoignable à la recopie Forge | snackbar avec le message du serveur |
| Serveur antérieur (pas de `hostRuleTitles`) | pas de lignes de recopie, rien ne casse |
| Échec du retrait | snackbar, liste inchangée |

## Critères d'acceptation
- [ ] CA1 — la liste s'affiche et [Retirer] la met à jour.
- [ ] CA2 — [Valable sur tous mes postes] appelle la route du poste avec le titre et rafraîchit la liste.
- [ ] CA3 — la carte COMPTE dit « Pour tous mes postes » ; la carte d'une règle de poste appliquée propose la recopie.
- [ ] CA4 — conforme `DESIGN_SYSTEM.md` (jetons `--cg-*`, boutons ≥ 44 px), 360 px sans débordement.

## Périmètre — hors scope
- Éditer le texte d'une règle COMPTE dans l'écran (l'agent ou la route s'en chargent).

## Technique
- `governance.service.ts` (+ list/delete/promoteFromHost), `governance.models.ts`, `governance-effective.component.ts`,
  `governance-proposal.service.ts` (+ promote), `proposal-card.component.ts`.

### Préoccupations transversales
- Navigation : non (aucune route nouvelle). Auth/tenant/plans : non.

## Plan de test
### Unitaires (composants)
- `governance-effective.component.spec.ts` : liste, retrait, recopie, « déjà sur tous mes postes », serveur antérieur.
- `proposal-card.component.spec.ts` : libellé COMPTE, bouton de recopie seulement pour REGLE POSTE/SUJET appliquée, succès/erreur.
### Intégration
- Couverte par SF-266-01 (routes). ### Isolation : côté serveur (SF-266-01).

## Dépendances
- SF-266-01.
