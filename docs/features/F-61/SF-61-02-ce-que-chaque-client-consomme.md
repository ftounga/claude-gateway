# Mini-spec — F-61 / SF-61-02 — Ce que chaque client consomme

---

## Identifiant

`F-61 / SF-61-02`

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

Rendre à l'utilisateur, en une lecture, **ce qu'il consomme par client** — par **poste** — et par
**projet** dessous, sur une période choisie, avec le coût estimé et la part de chacun : la matière
d'une refacturation.

---

## Comportement attendu

### Cas nominal

1. `GET /api/usage/by-client?from=2026-04-01&to=2026-09-01` rend, pour l'utilisateur **du JWT** :
   - les bornes effectives de la fenêtre et la devise ;
   - les totaux de la fenêtre (entrée, sortie, total, coût estimé) ;
   - la liste des **clients** (postes), du plus consommateur au moins, chacun avec ses volumes,
     son coût estimé, sa **part du total** et la liste de ses **projets**, eux-mêmes triés par
     consommation décroissante.
2. **Entrée et sortie ne sont jamais confondues** : deux nombres, deux tarifs (5 €/M contre 25 €/M),
   et le coût est calculé séparément puis additionné — exactement la formule de F-16, désormais
   portée par un estimateur unique et partagé.
3. **Fenêtre par défaut** : les 12 derniers mois, `from` = premier du mois d'il y a 11 mois,
   `to` = premier du mois courant (borne **incluse**, au grain du mois). Les deux paramètres sont
   facultatifs et indépendants.
4. **Un seau « Hors client »** (poste nul) rassemble les tours sans poste : projets en bac à sable,
   `/chat`, `/ask`. Il est rendu **en dernier**, quel que soit son volume, et porte `hostId: null`.
   La somme des clients égale toujours le total (arbitrage A-3).
5. **Nommage à la lecture** : le nom du poste et celui du projet sont résolus au moment de la
   requête, filtrés `user_id`. Un poste ou un projet supprimé depuis rend `name: null` — l'écran
   dira « supprimé », la dépense reste comptée.
6. **Aucune consommation sur la fenêtre** → `200` avec des totaux à zéro et une liste vide. Pas
   d'erreur : ne pas avoir consommé n'est pas une faute.
7. **Isolation** : l'identifiant vient exclusivement de `CurrentUser`. Aucun paramètre client ne
   désigne un utilisateur, un poste ou un projet.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `from` postérieur à `to` | « La date de début doit précéder la date de fin. » | 400 |
| Fenêtre de plus de 24 mois | « La période demandée dépasse 24 mois. » | 400 |
| `from`/`to` non parsable (`2026-13-99`, `hier`) | Rejet de conversion | 400 |
| Date au milieu d'un mois (`2026-04-17`) | **Normalisée** au premier du mois, pas d'erreur | 200 |
| Appel sans JWT | Non authentifié | 401 |
| Aucun poste, aucun projet | Totaux à zéro, `clients: []` | 200 |

---

## Critères d'acceptation

- [ ] `GET /api/usage/by-client` sans paramètre → `200`, fenêtre de 12 mois se terminant au mois
      courant.
- [ ] Deux projets sous le **même** poste sont rendus **sous une seule entrée client**, dont les
      volumes sont la somme des leurs.
- [ ] Deux projets sous **deux** postes produisent deux entrées client distinctes.
- [ ] Les tours sans poste sont rendus dans l'entrée « Hors client » (`hostId: null`), placée en
      dernier.
- [ ] La somme des `totalTokens` des clients **égale** le `totalTokens` de la réponse.
- [ ] La somme des `share` vaut 1 à l'arrondi près ; `share` vaut 0 quand le total est nul (aucune
      division par zéro).
- [ ] Le coût estimé d'un client dont l'entrée et la sortie diffèrent **n'est pas** le total × un
      tarif moyen : il applique les deux tarifs séparément.
- [ ] `from > to` → `400` ; fenêtre > 24 mois → `400`.
- [ ] **Isolation** : les tours de l'utilisateur B n'apparaissent jamais dans la réponse de A, même
      si B possède un poste portant le même nom.
- [ ] **Aucun contenu** n'apparaît dans la réponse : ni message, ni commande, ni chemin
      (`project_path` n'est pas exposé).

---

## Périmètre

### Hors scope (explicite)

- Agir sur les quotas depuis cette réponse (aucune route d'écriture).
- L'export comptable et la facture — hors périmètre de la feature.
- Le détail **par tour** : la réponse agrège. Rendre la liste des tours reviendrait à rendre une
  chronologie d'activité, ce que l'utilisateur a déjà dans son fil.
- Le temps de bac à sable : le journal mesure des tokens (SF-61-01).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|-----------------------------|---------------|
| `from` | Non | date ISO `yyyy-MM-dd` | ramenée au 1er du mois (UTC) ; défaut : `to` − 11 mois |
| `to` | Non | date ISO `yyyy-MM-dd` | ramenée au 1er du mois (UTC) ; défaut : mois courant |

Notes :
- Fenêtre maximale **24 mois** (arbitrage A-6), constante de service `MAX_WINDOW_MONTHS`.
- La borne haute est **incluse au grain du mois** : `to = 2026-09-01` inclut tout septembre.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/usage/by-client` | Oui (JWT) | USER (tout compte, sur ses seules données) |

Forme de la réponse :

```json
{
  "currency": "EUR",
  "from": "2026-04-01", "to": "2026-09-01",
  "inputTokens": 1200000, "outputTokens": 240000, "totalTokens": 1440000,
  "estimatedCost": 12.0000,
  "clients": [
    { "hostId": "…", "hostName": "poste-groupe-x", "inputTokens": 900000,
      "outputTokens": 180000, "totalTokens": 1080000, "estimatedCost": 9.0000, "share": 0.75,
      "projects": [ { "workspaceId": "…", "name": "refonte-paie", "inputTokens": 900000,
                      "outputTokens": 180000, "totalTokens": 1080000,
                      "estimatedCost": 9.0000, "share": 0.75 } ] }
  ]
}
```

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `usage_turns` | SELECT agrégé, filtré `user_id` | Source de vérité (SF-61-01) |
| `runner_hosts` | SELECT (noms), filtré `user_id` | Résolution des libellés |
| `workspaces` | SELECT (noms), filtré `user_id` | Résolution des libellés |

### Migration Liquibase

- [x] Non applicable (la table vient de SF-61-01)

---

## Plan de test

### Tests unitaires

- [ ] `UsageByClientServiceTest` — regroupement de deux projets sous un poste.
- [ ] `UsageByClientServiceTest` — seau « Hors client » rendu en dernier.
- [ ] `UsageByClientServiceTest` — parts calculées, somme = 1 ; total nul → parts à 0.
- [ ] `UsageByClientServiceTest` — coût : entrée et sortie tarifées séparément.
- [ ] `UsageByClientServiceTest` — fenêtre par défaut, normalisation au 1er du mois.
- [ ] `UsageByClientServiceTest` — `from > to` et fenêtre > 24 mois → `IllegalArgumentException`.
- [ ] `UsageCostEstimatorTest` — la formule extraite rend **exactement** ce que rendait F-16.

### Tests d'intégration

- [ ] `GET /api/usage/by-client` → `200`, structure et totaux conformes.
- [ ] `GET /api/usage/by-client?from=…&to=…` avec `from > to` → `400`.
- [ ] Sans JWT → `401`.
- [ ] **Isolation** : deux comptes, deux postes homonymes, chacun ne voit que le sien.

### Isolation workspace / utilisateur

- [x] Applicable — test d'intégration dédié à deux comptes.

---

## Dépendances

### Subfeatures bloquantes

- `SF-61-01` — même PR, mergée ensemble (arbitrage A-5).

---

## Notes et décisions

- **Pourquoi un endpoint distinct de `/usage/report`** : le rapport F-16 répond « combien ce
  mois-ci », celui-ci « pour qui ». Les fusionner obligerait tout appelant du rapport à charger une
  agrégation de jointure dont il n'a pas besoin — et l'écran de F-16 est chargé à chaque visite.
- **L'estimateur de coût est extrait** (`UsageCostEstimator`) et `UsageReportService` l'utilise :
  deux définitions du coût divergeraient un jour, et le jour où elles divergent, deux écrans du même
  produit annoncent deux montants différents pour la même consommation.
