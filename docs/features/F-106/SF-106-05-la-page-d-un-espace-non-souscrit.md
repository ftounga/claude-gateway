# Mini-spec — F-106 / SF-106-05 — La page d'un espace non souscrit

## Identifiant

`F-106 / SF-106-05`

## Feature parente

`F-106` — La Vigie, l'espace du pilotage (cadrage : `CADRAGE-F-106-la-vigie.md` §4, §6 ; offre :
`docs/features/F-107/CADRAGE-F-107-l-offre-par-espace.md` §9)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-106-05-espace-non-souscrit`

---

## Objectif

Quand un compte ouvre un espace qu'il n'a pas, lui montrer une **page de présentation de cet
espace avec l'essai**, jamais une erreur ni un écran vide.

---

## Comportement attendu

### Cas nominal

1. Un composant unique **`app-space-pitch`** (`space` = `FORGE` | `VIGIE`) présente l'espace :
   - titre (« La Vigie : ce qu'on attend de vous, sans avoir à le demander » / « La Forge : livrer
     sur la machine de vos clients ») ;
   - trois points concrets de ce qu'on y fait (Vigie : le Radar du matin, les conversations Teams,
     les réunions et l'annuaire ; Forge : les terminaux sur la machine du client, la carte de
     l'infrastructure, la gouvernance) ;
   - rappel « un client, deux espaces » : les clients déjà connectés s'y activent sans réappairage ;
   - **l'essai** : « Essai de deux semaines avec un code d'accès » (F-107 §9 pour la Vigie ; accès
     offert F-62 pour la Forge) — action principale **« J'ai un code d'essai »** vers
     `/billing#code-acces`, action secondaire **« Voir les formules »** vers `/billing`.
2. **Vigie** : un compte sans le droit (droit Teams en attendant F-107), ou dont la lecture du droit
   échoue, voit la page de présentation de la Vigie **dans** l'écran `/vigie` (la barre du haut garde
   l'entrée Vigie visible), sans bandeau d'actions ni lecture des clients.
3. **Forge** : un compte sans accès Forge (403 sur la vue) voit la page de présentation de la Forge à
   la place de l'encart d'accès ; le sondage reste arrêté (F-85 inchangé).
4. **Aucun montant n'est affiché** : les prix de F-107 ne sont pas encore facturables (SF-107-03) ;
   la page renvoie aux formules.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Lecture du droit Teams en échec | page de présentation de la Vigie (fail-closed), pas d'erreur | 5xx / 0 |
| Vue des postes refusée | page de présentation de la Forge, sondage arrêté | 403 |
| `space` inconnu passé au composant | présentation de la Forge | — |

---

## Critères d'acceptation

- [ ] `/vigie` sans droit montre la présentation de la Vigie, avec l'essai et les formules, sans
      lecture des clients.
- [ ] `/forge` sans accès montre la présentation de la Forge, le lien principal pointant
      `/billing#code-acces`.
- [ ] Aucun écran vide, aucun message d'erreur, aucun montant.
- [ ] Aucune couleur hors `DESIGN_SYSTEM.md`.

---

## Périmètre

### Hors scope (explicite)

- Le code d'essai Vigie lui-même, son enveloppe et la mesure du coût (F-107 / SF-107-04).
- Les options et plans par espace, les montants, Stripe (F-107 / SF-107-03).
- Un parcours d'achat depuis la page : on renvoie à la facturation.

---

## Valeurs initiales

Aucune.

---

## Contraintes de validation

Aucune saisie.

---

## Technique

### Endpoint(s)

Aucun (lus sans changement : `GET /api/teams/access`, `GET /api/runner-hosts/overview`).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `shared/space-pitch/space-pitch.component.*` (nouveau).
- `VigieComponent` — remplace l'encart « non ouverte » par `app-space-pitch space="VIGIE"`.
- `PostesComponent` — remplace l'encart d'accès par `app-space-pitch space="FORGE"`.

### Préoccupations transversales

- **Plans / limites : oui (lecture).** Composants vérifiés : `VigieComponent` (droit Teams lu une
  fois, fail-closed), `PostesComponent` (403 sur la vue ⇒ présentation, sondage arrêté),
  `openForgeAccessSnackBar` (refus au geste : inchangé). Aucune garde nouvelle.
- **Navigation / routing : oui.** Liens vers `/billing` et `/billing#code-acces` (ancre existante
  F-85) ; aucune route ajoutée.
- Auth / Principal : non. Contexte tenant : non.

---

## Plan de test

- [ ] `space-pitch.component.spec.ts` — contenus Forge et Vigie, essai, liens, aucun montant.
- [ ] `vigie.component.spec.ts` — sans droit ou droit illisible : présentation de la Vigie, aucune
      lecture des clients.
- [ ] `postes.component.spec.ts` — 403 : présentation de la Forge, lien vers le code, sondage arrêté.

### Isolation workspace

- [x] Non applicable.

---

## Dépendances

### Subfeatures bloquantes

- SF-106-02 — `done`.

### Questions ouvertes impactées

- Aucune.
