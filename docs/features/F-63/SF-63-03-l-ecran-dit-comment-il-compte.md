# Mini-spec — F-63 / SF-63-03 — L'écran dit comment il compte

---

## Identifiant

`F-63 / SF-63-03`

## Feature parente

`F-63` — Le quota compte au coût réel (`docs/PRODUCT_SPEC.md`)

## Statut

`ready`

## Date de création

2026-09-11

## Branche Git

`feat/SF-63-03-ecran-decompte-pondere`

---

## Objectif

Dire à l'utilisateur, là où un quota est montré, que le décompte est **pondéré au coût réel** — sans
quoi l'écran de consommation livré par F-61 laisserait croire à un comptage brut et deux chiffres
justes passeraient pour une contradiction.

---

## Comportement attendu

### Cas nominal

1. **Écran d'abonnement** (`/billing`), là où s'affiche « *N* / *Q* tokens » : la jauge porte une
   mention courte expliquant que le décompte est **pondéré au coût réel** — un token de sortie pèse
   davantage qu'un token d'entrée, une lecture de cache pèse moins — et que le **volume traité** est
   visible sur l'écran de consommation.
2. **Écran de consommation** (`/reports`, F-61) : la phrase d'introduction distingue explicitement
   les deux chiffres — ce que la page montre (des **volumes traités** et leur **coût estimé**, par
   mois et par client) et ce que le quota oppose (un **décompte pondéré**), avec le volume et le
   décompte de la période courante côte à côte.
3. Aucune couleur, police ou composant hors `docs/DESIGN_SYSTEM.md` ; aucun `.scss` existant
   réécrit (la passe F-56 reste intacte) — on réutilise les classes de note déjà en place.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `GET /usage` indisponible | comportement actuel (la jauge ne s'affiche pas) ; la mention disparaît avec elle | — |
| `processedTokens` absent de la réponse (backend ancien) | la mention reste, le volume n'est simplement pas affiché | — |

---

## Critères d'acceptation

- [ ] L'écran d'abonnement nomme le décompte pondéré à côté de la jauge de quota.
- [ ] L'écran de consommation dit, en une phrase, que ses tokens sont des **volumes** et que le
      quota se décompte autrement, et montre les deux chiffres de la période courante.
- [ ] Aucun montant, aucun tarif, aucun ratio chiffré n'est écrit en dur dans le frontend : la page
      explique la **règle**, jamais les prix (ils vivent en configuration backend).
- [ ] Tests frontend verts ; aucune régression de rendu sur les écrans touchés.

---

## Plan de test minimal

**Unitaires (composants)**
- `billing.component.spec.ts` : la mention est rendue quand la consommation est chargée.
- `reports.component.spec.ts` : la phrase distingue volume et décompte ; le volume de la période
  s'affiche quand il est fourni, et son absence ne casse rien.

**Intégration**
- Aucun appel réseau nouveau : `GET /usage` est déjà consommé par l'écran d'abonnement.

**Isolation utilisateur**
- Inchangée : les deux écrans lisent les routes existantes, portées par le JWT de l'utilisateur.

---

## Tables / endpoints / composants impactés

Aucune table, aucun endpoint nouveau. `billing.component.html`, `reports.component.html/.ts`,
`usage.models.ts` (champ `processedTokens`), et leurs specs.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | — |
| Plans / limites | non | aucun gate touché — l'écran informe, il ne décide pas |
| Navigation / routing | non | aucune route ajoutée ou modifiée |

---

## Hors périmètre

Afficher des prix ou des ratios chiffrés ; un écran de détail du calcul ; toute refonte visuelle.
