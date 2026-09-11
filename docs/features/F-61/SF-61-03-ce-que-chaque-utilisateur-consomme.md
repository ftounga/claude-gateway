# Mini-spec — F-61 / SF-61-03 — Ce que chaque utilisateur consomme (console admin)

---

## Identifiant

`F-61 / SF-61-03`

## Feature parente

`F-61` — Consommation : par client pour chacun, par utilisateur pour l'admin

## Statut

`ready`

## Date de création

2026-09-11

## Branche Git

`feat/SF-61-01-releve-et-agregation`

---

## Objectif

Donner à l'administrateur — **rôle `ADMIN`, sans exception** — la consommation **par utilisateur**
sur une période choisie : tokens **d'entrée et de sortie distingués**, coût estimé, **part du
total**, plan, et **évolution mois par mois** — et **rien d'autre** : aucun contenu, aucun projet,
aucun poste.

---

## Comportement attendu

### Cas nominal

1. `GET /api/admin/usage?from=2026-04-01&to=2026-09-01` rend :
   - la devise et les bornes effectives ;
   - les totaux de la plateforme sur la fenêtre (entrée, sortie, total, coût estimé) ;
   - la liste des comptes **ayant consommé**, du plus consommateur au moins : identité (e-mail,
     déjà rendue par `GET /admin/users`), rôle, plan et statut d'abonnement, volumes **d'entrée** et
     **de sortie** séparés, coût estimé, **part du total**, et l'**évolution mensuelle** — une entrée
     par mois de la fenêtre où le compte a consommé.
2. **La garde est celle qui existe déjà** : `AdminService.assertAdmin()` — rôle `ADMIN` **ou**
   super-admin configuré. Une seconde définition de « qui est admin » divergerait un jour.
3. **La source est `usage_counters`** (F-10), monotone et référence de facturation (arbitrage A-4).
   Le **journal par tour** n'est **pas** lu ici : il connaît les projets, et une console admin bâtie
   dessus serait un pas vers la surveillance.
4. **Entrée et sortie ne sont jamais confondues**, ni dans les lignes, ni dans le coût : deux tarifs
   distincts appliqués séparément (même estimateur que SF-61-02).
5. Un compte **sans consommation** sur la fenêtre n'apparaît pas : la liste des comptes reste
   `GET /admin/users`, celle-ci répond « qui consomme, combien ».
6. **Fenêtre** : mêmes règles que SF-61-02 — défaut 12 mois, normalisation au 1er du mois, plafond
   dur 24 mois.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Appelant `USER` (non admin) | Refus, **aucune donnée** rendue | 403 |
| Appel sans JWT | Non authentifié | 401 |
| `from` postérieur à `to` | Message explicite | 400 |
| Fenêtre > 24 mois | Message explicite | 400 |
| Date non parsable | Rejet de conversion | 400 |
| Aucune consommation sur la plateforme | Totaux à zéro, `users: []` | 200 |

---

## Critères d'acceptation

- [ ] `GET /api/admin/usage` avec un JWT `ADMIN` → `200`.
- [ ] Avec un JWT `USER` → `403`, **corps sans aucune donnée d'usage**.
- [ ] Sans JWT → `401`.
- [ ] La réponse distingue `inputTokens` et `outputTokens` pour **chaque** utilisateur, et le coût
      estimé applique les deux tarifs séparément (un compte à 1 M d'entrée et 1 M de sortie coûte
      5 € + 25 €, jamais 2 M × un tarif moyen).
- [ ] `share` = part du total de la fenêtre ; somme = 1 à l'arrondi près ; 0 si le total est nul.
- [ ] `periods` rend l'évolution mensuelle, **du plus ancien au plus récent**, bornée à la fenêtre.
- [ ] Le plan et le statut d'abonnement de chaque compte sont rendus.
- [ ] **Aucun nom de projet, aucun nom de poste, aucun contenu** n'apparaît dans la réponse —
      vérifié par un test qui parcourt la charge utile.
- [ ] Restreindre la fenêtre restreint effectivement les nombres (un mois hors fenêtre ne compte pas).

---

## Périmètre

### Hors scope (explicite)

- **Agir sur les quotas** (créditer, plafonner, suspendre) depuis cet écran ou cette route.
- L'export comptable, la facture.
- La consommation **par projet ou par poste** d'un utilisateur donné : c'est sa vue à lui
  (SF-61-02), pas celle de l'administrateur. C'est la limite de la feature, pas un manque.
- Toute donnée de contenu, y compris indirecte (titre de conversation, nom de projet).

---

## Contraintes de validation

| Champ | Obligatoire | Format | Normalisation |
|-------|-------------|--------|---------------|
| `from` | Non | date ISO | 1er du mois (UTC) ; défaut `to` − 11 mois |
| `to` | Non | date ISO | 1er du mois (UTC) ; défaut mois courant |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/admin/usage` | Oui (JWT) | **ADMIN** (ou super-admin configuré) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `usage_counters` | SELECT sur la fenêtre | Source de vérité admin |
| `users` | SELECT (e-mail, rôle) | Identité du compte |
| `subscriptions` | SELECT (plan, statut) | Déjà lu par `AdminService` |

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Tests unitaires

- [ ] `AdminUsageServiceTest` — agrégation par utilisateur, tri décroissant.
- [ ] `AdminUsageServiceTest` — entrée et sortie distinguées, coût appliqué séparément.
- [ ] `AdminUsageServiceTest` — parts (somme = 1 ; total nul → 0).
- [ ] `AdminUsageServiceTest` — évolution mensuelle croissante et bornée à la fenêtre.
- [ ] `AdminUsageServiceTest` — un compte sans consommation est absent.
- [ ] `AdminUsageServiceTest` — appelant non admin → `AdminForbiddenException`.

### Tests d'intégration

- [ ] `ADMIN` → `200` avec deux comptes agrégés.
- [ ] `USER` → `403`.
- [ ] anonyme → `401`.
- [ ] Fenêtre restreinte → nombres restreints.
- [ ] **Confidentialité** : la charge utile ne contient ni nom de projet, ni nom de poste
      (contrôle explicite sur une base qui en contient).

### Isolation

- [x] Applicable — l'agrégation admin est **transverse par construction** (c'est son objet) ; le
      test d'isolation porte donc sur la **garde** : un non-admin n'obtient rien.

---

## Dépendances

### Subfeatures bloquantes

- `SF-61-02` — partage de l'estimateur de coût et des règles de fenêtre (même PR).

---

## Notes et décisions

- **Pourquoi ne pas enrichir `GET /admin/users`** : cette route répond « qui sont les comptes » et
  est chargée à chaque ouverture de la console. Y greffer une agrégation sur douze mois de
  compteurs ferait payer à tout le monde le prix d'un écran qu'on ouvre parfois.
- **`totalTokens` reste sur `/admin/users`** : inchangé, pour ne casser aucun appelant existant.
