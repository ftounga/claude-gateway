# Mini-spec — [F-173 / SF-173-04] La grille des environnements

## Identifiant

`F-173 / SF-173-04`

## Feature parente

`F-173` — La carte vivante (cadrage `CADRAGE-F-173-la-carte-vivante.md`, D1→D7 validées le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-173-04-grille-environnements`

---

## Objectif

Montrer d'un coup d'œil, en grille **domaine × environnement** (Sandbox → Prod), où sont les comptes et plateformes du client, leur état et le rôle accordé.

---

## Comportement attendu

### Cas nominal

1. Une vue **Grille** s'ajoute à la bascule : *Plan · Liste · Grille · Fichiers* (`?vue-carte=grille`).
2. Elle est calculée côté écran sur le plan déjà lu (contrat SF-173-01, aucun appel de plus) : toute ressource qui porte un **environnement** ou un **domaine** y figure.
3. **Colonnes** = environnements, dans l'ordre *Sandbox → Dev → Recette / Test → Hors-prod / Pré-prod / Staging → Prod*, puis les autres environnements nommés (ordre alphabétique), puis « Non précisé ». Les synonymes sont rapprochés (`preprod`, `pre-prod`, `staging` → Hors-prod ; `production`, `prd` → Prod ; `qa`, `test`, `uat`, `recette` → Recette ; `development`, `developpement` → Dev).
4. **Lignes** = domaines métier (ordre alphabétique), puis « Sans domaine ».
5. Chaque cellule liste ses ressources : nom, type, **état** (« joignable », « injoignable »…), **rôle accordé** (relation `accorde` reçue : « accordé par X »), marque « ⚠ n » s'il y a des pièges, « périmé » s'il l'est. Un clic ouvre la fiche (SF-173-03) via `?noeud=`.
6. Sous 768 px, la grille devient une liste par environnement (même contenu).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucune ressource n'a d'environnement ni de domaine | « La carte ne dit encore ni environnement ni domaine pour ses ressources. » |
| Carte non indexée / plan illisible | même message que le plan (SF-173-02) |
| Environnement inconnu (« perf ») | colonne à part, après Prod, libellé tel quel |

---

## Critères d'acceptation

- [x] Colonnes ordonnées Sandbox → Prod avec synonymes rapprochés ; lignes par domaine.
- [x] Chaque cellule dit nom, état, rôle accordé, pièges, périmé ; un clic ouvre la fiche.
- [x] Aucun appel réseau de plus que le plan.
- [x] Mobile : liste par environnement.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Ressources par cellule affichées | ≤ 12, puis « + n autres » |
| Ordre des colonnes | sandbox, dev, recette, hors-prod, prod, autres (A→Z), non précisé |

---

## Périmètre

### Hors scope (explicite)

- Toute édition d'environnement ou de domaine (la carte se modifie par la Forge).
- Les rôles IAM détaillés (seule la relation `accorde` de la carte est lue).

---

## Technique

### Contrat API

Importé de SF-173-01 (plan), sans modification.

### Composants frontend

- `postes/forge-map/forge-map-grid.ts` : fonctions pures (`envKey`, `environmentGrid`).
- `postes/forge-map/forge-map.component.*` : vue `grille`.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Navigation / routing | Oui | nouvelle valeur `grille` de `?vue-carte=` lue par `mapViewFrom` / `ForgeMapComponent.selectView` ; `?onglet=`, `/forge/:hostRef` inchangés |
| Auth, tenant, plans | Non | — |

---

## Plan de test

- [x] `forge-map-grid.spec.ts` : ordre des colonnes, synonymes, environnement inconnu, lignes par domaine, rôle accordé, borne par cellule, grille vide.
- [x] `forge-map.component.spec.ts` : vue Grille, clic → `?noeud=`.
- [x] `npm run build && npm test` verts.

## Dépendances

SF-173-01 (mergée), SF-173-03 (fiche) mergée.

## Notes et décisions

- **Arbitrage (réversible)** : grille calculée côté écran sur le plan déjà lu — pas de second endpoint.
- **Arbitrage (réversible)** : « rôle accordé » = relation `accorde` dite par la carte ; à défaut, rien n'est inventé.
