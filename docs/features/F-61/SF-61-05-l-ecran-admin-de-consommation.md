# Mini-spec — F-61 / SF-61-05 — L'écran admin de consommation

---

## Identifiant

`F-61 / SF-61-05`

## Feature parente

`F-61` — Consommation : par client pour chacun, par utilisateur pour l'admin

## Statut

`ready`

## Date de création

2026-09-11

## Branche Git

`feat/SF-61-04-les-ecrans-de-consommation`

---

## Objectif

Donner à la console `/admin` la section qui lui manquait : **qui consomme, combien, à quel coût, sur
quelle période** — entrée et sortie distinguées, part du total, plan, évolution — et **rien
d'autre**.

---

## Comportement attendu

### Cas nominal

1. Sur `/admin`, au-dessus de la liste des comptes, une section **« Consommation »** appelle
   `GET /api/admin/usage` et rend :
   - trois cartes de synthèse : **tokens d'entrée**, **tokens de sortie**, **coût estimé** de la
     plateforme sur la fenêtre — l'entrée et la sortie **ne sont jamais additionnées** dans une
     carte unique, parce que leurs coûts unitaires n'ont rien à voir ;
   - un tableau par compte : e-mail, plan, entrée, sortie, total, coût estimé, **part** (barre), et
     une **micro-évolution** mensuelle (barres, une par mois de la fenêtre).
2. Un sélecteur **3 / 6 / 12 mois** recharge la section.
3. La liste des comptes existante (F-20) reste en dessous, **inchangée**.
4. **États** : chargement, vide (« Aucune consommation sur cette période. »), erreur (snackbar,
   la liste des comptes reste affichée).
5. Une mention rappelle que le coût est une **estimation** au tarif configuré, pas un montant
   facturé — la même phrase que sur l'écran utilisateur.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| API 403 (compte non admin ayant forcé la route) | Section non rendue, snackbar ; aucune donnée partielle |
| API 5xx / réseau | Snackbar, liste des comptes toujours rendue |
| Aucune consommation | Carte d'état vide |
| Total nul | Barres à 0 %, aucune division par zéro |

---

## Critères d'acceptation

- [ ] La section n'apparaît que dans `/admin`, dont l'accès est déjà réservé (lien conditionné à
      `isAdmin`, API `403` sinon).
- [ ] **Entrée et sortie sont distinguées** partout : cartes de synthèse et colonnes du tableau.
- [ ] Coût estimé et part du total rendus par compte.
- [ ] Le plan de chaque compte est rendu.
- [ ] Le sélecteur de période recharge la section et met à jour les totaux.
- [ ] L'évolution mensuelle est visible par compte.
- [ ] **Aucun nom de projet, aucun nom de poste, aucun contenu** n'est affiché — l'API n'en rend
      aucun, l'écran n'en invente pas.
- [ ] Liste des comptes F-20 inchangée.
- [ ] Charte respectée : jetons `--cg-*`, typographie et espacements du design system ; aucune
      couleur nouvelle.
- [ ] Écran étroit : le tableau défile dans son conteneur, la page ne déborde pas.
- [ ] Tests de composant verts.

---

## Périmètre

### Hors scope (explicite)

- **Agir sur les quotas** : aucun bouton, aucun champ, aucune route d'écriture.
- L'export comptable, la facture.
- Le détail par projet ou par poste d'un utilisateur : limite de la feature (cadrage §3).

---

## Technique

### Composants Angular

- `AdminComponent` — accueille la section.
- `AdminUsageService` / `admin-usage.models.ts` — appel et types.

---

## Plan de test

### Tests de composant

- [ ] Rendu nominal : deux comptes, totaux, parts, évolution.
- [ ] Changement de période → nouvel appel.
- [ ] État vide.
- [ ] Erreur API → snackbar, liste des comptes toujours rendue.

### Isolation / sécurité

- [x] Applicable — la garde est côté API (`403`). L'écran ne rend rien en cas de refus : aucune
      donnée partielle ne doit subsister à l'affichage.

---

## Dépendances

- `SF-61-03` — mergée avant (backend d'abord).

---

## Notes et décisions

- **Pourquoi dans `/admin` et non une route `/admin/usage`** : la console admin est déjà l'écran
  unique où l'on regarde ce qu'un admin peut voir (comptes, gouvernance, codes d'accès). Un
  quatrième onglet de navigation pour une section de lecture coûterait plus qu'il ne rapporte.
