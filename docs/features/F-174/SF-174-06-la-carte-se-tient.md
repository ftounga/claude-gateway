# Mini-spec — [F-174 / SF-174-06] La carte se tient

## Identifiant

`F-174 / SF-174-06`

## Feature parente

`F-174` — La carte qui répond (cadrage `CADRAGE-F-174-la-carte-qui-repond.md`, D9 validée le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-174-06-la-carte-se-tient`

---

## Objectif

Détecter dans la carte d'un poste les doublons, contradictions, faits périmés et échéances dépassées, et les exposer en API comme des propositions de consolidation — jamais appliquées seules.

---

## Comportement attendu

### Cas nominal

`GET /api/governance/hosts/{hostRef}/map/consolidation` → `{ indexed, total, proposals[] }`, chaque proposition : `kind`, `path`, `summary`, `facts[]` (`path`, `heading`, `lineNo`, `text`), `request` (la demande à confier à la Forge, qui montre le changement avant d'écrire).

Familles, calculées sur l'index sans appel au modèle :

| `kind` | Règle |
|---|---|
| `DOUBLON` | même fait (texte normalisé : sans puce, date de constat, casse, espaces, ponctuation finale ; ≥ 12 caractères) écrit au moins deux fois, toutes sections et fichiers confondus |
| `CONTRADICTION` | deux sections de même titre normalisé dans un fichier (« Cause réelle » ×2) ; ou une même ressource dite dans deux états différents |
| `PERIME` | faits dont la date de constat dépasse `app.governance.map.fact-max-age-days` (120 j), groupés par section |
| `ECHEANCE_DEPASSEE` | échéance passée encore inscrite |

Au plus 100 propositions (le total réel est rendu), 12 faits cités par proposition. **D1** : rien n'est écrit ; l'écran F-173 propose « Demander à la Forge » avec `request`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Sans jeton | Refusé | 401 |
| Poste d'un autre compte / inconnu | Introuvable (jamais « interdit ») | 404 |
| « Hébergé », index éteint ou carte non indexée | `indexed: false`, liste vide | 200 |
| Sans accès Forge | Refus de l'accès Forge (garde existante) | 403 |

---

## Critères d'acceptation

- [x] Chaque famille est détectée sur un cas représentatif.
- [x] Aucune écriture (lecture seule, transaction `readOnly`).
- [x] Garde identique à `GET …/map` ; poste d'autrui = 404.
- [x] Isolation : toutes les lectures `(user_id, host_id)`.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Propositions | ≤ 100 rendues, `total` = nombre réel |
| Faits par proposition | ≤ 12 |

---

## Périmètre

### Hors scope (explicite)

- L'écran et le bouton « Demander à la Forge » : F-173 (SF-173-07).
- Toute application automatique (D1, D9).
- La détection sémantique de contradictions par le modèle (possible plus tard ; ici, règles sûres).

---

## Technique

### Endpoint

- **Nouveau** `GET /governance/hosts/{hostRef}/map/consolidation` (`HostMapConsolidationController`), garde `AtelierAccessService.requireAccess()` + `GovernanceHostScope.require`.

### Composants backend

- `governance/map/index/HostMapConsolidation` (règles), `HostMapConsolidationController`.

### Tables

Aucune nouvelle (lecture de l'index SF-174-02).

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Auth / Principal | Oui | nouvel endpoint sous `/governance/hosts/**`, même garde que `GovernanceHostController` (JWT + accès Forge) ; aucun endpoint existant modifié |
| Contexte tenant | Oui | `GovernanceHostScope.require(userId, hostRef)` (404 hors compte) ; requêtes `(user_id, host_id)` |
| Plans, routing | Non | — |

---

## Plan de test

- [x] `HostMapConsolidationTest` : chaque famille, carte non indexée, normalisation.
- [x] `HostMapConsolidationApiIntegrationTest` : 401, 404 poste d'autrui, 200 propriétaire, « Hébergé ».
- [x] Suite complète verte.

## Dépendances

SF-174-02 mergée.

## Notes et décisions

- **Arbitrage (réversible)** : règles déterministes seulement (aucun coût, aucun faux positif « inventé ») ; une détection sémantique pourra s'ajouter si la mesure le justifie.
- **Arbitrage (réversible)** : chaque proposition porte une demande en français prête à envoyer à la Forge, qui impose « montre-moi le changement avant de l'écrire ».
