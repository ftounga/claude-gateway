# Mini-spec — [F-101 / SF-101-05] La réserve et la mesure

---

## Identifiant

`F-101 / SF-101-05`

## Feature parente

`F-101` — Le Radar : la lecture des échanges
(cadrage validé : `docs/features/F-99/CADRAGE-le-radar.md`, §7 « Enveloppe », §11, §12 bis, §14 ;
grille décidée F-107 : « réserve de synchro 3 M jetons par client suivi, revalidée après l'essai »)

## Statut

`done` — livrée le 2026-09-13 (PR #517)

## Date de création

2026-09-13

## Branche Git

`feat/SF-101-05-reserve-et-mesure`

---

## Objectif

Faire **s'arrêter proprement** l'analyse d'un poste quand sa **réserve de synchro** est épuisée — sans
perdre les lots, et en le disant —, et **relever par synchro** ce que l'analyse a coûté et combien de
rattachements l'utilisateur a dû corriger, pour l'essai de deux semaines de F-107.

---

## Contexte

Cadrage §7 : « chaque synchro connaît son plafond de consommation. Plafond atteint : elle s'arrête
proprement, garde son curseur, et le résumé le dit ». §11 : la synchro **ne mange jamais le quota des
conversations** — elle a sa propre enveloppe. Le **droit et le montant** de la réserve relèvent de
l'option Vigie (**SF-107-04**, non livrée) : cette SF pose le **mécanisme d'arrêt** derrière une
interface, avec une réserve **configurée** par défaut que SF-107-04 remplacera. §14 : la qualité du
rattachement « décidera de l'adoption » — mesure prévue ici.

---

## Comportement attendu

### Cas nominal

1. **La réserve** (`RadarReserve`, interface) rend, pour un poste et une synchro, les jetons encore
   disponibles. Implémentation par défaut `ConfiguredRadarReserve` :
   - **mensuelle par poste** : `app.radar.reserve.monthly-tokens` (défaut **3 000 000**), moins la
     consommation des synchros du poste **commencées dans le mois civil UTC** (`radar_syncs.consumed_tokens`) ;
   - **plafond par synchro** facultatif : `app.radar.reserve.per-sync-tokens` (défaut 0 = aucun), moins
     la consommation de la synchro ;
   - disponible = le plus petit des deux ; **en BYOK aussi** (la réserve protège la facture de la clé).
2. **L'arrêt propre** (`RadarExchangeAnalyzer`) :
   - **avant le tri** : disponible ≤ 0 → `DEFER` code `RESERVE_EXHAUSTED`, **aucun appel** ; échéance =
     1ᵉʳ du mois suivant (00:00 UTC) si c'est la réserve mensuelle, sinon le délai de report de la file ;
   - **entre le tri et l'extraction** : disponible − jetons du tri ≤ 0 → `DEFER` (`RESERVE_EXHAUSTED`),
     jetons du tri comptés, **rien n'est écrit** ;
   - un lot reporté **garde son texte brut** (jusqu'à l'expiration, 7 jours) : il reprend dès que la
     réserve le permet — c'est le « curseur gardé » ;
   - le dépassement d'un appel en cours est borné par ses plafonds de sortie (tri, extraction) : la
     réserve est une limite de **déclenchement**, pas une coupure au jeton près.
3. **La mesure par synchro** (`GET /api/radar/hosts/{hostId}/syncs`, champ `analysis`) gagne :
   - `costUsd` : coût **estimé aux tarifs configurés** (`BilledTokensCalculator.costUsd`) des jetons de
     l'analyse, par nature ;
   - `stoppedOnReserve` : vrai si au moins un lot de la synchro est reporté pour réserve épuisée ;
   - `unreadBatches` : lots `FAILED` + `EXPIRED` (échecs de couverture) ;
   - `attachmentCorrections` : corrections **« fusionner »** (c'était le même sujet) et **« séparer »**
     (ce n'était pas le même sujet), non annulées, faites **entre le début de cette synchro et le début
     de la suivante** ;
   - `attachmentCorrectionRate` : `attachmentCorrections ÷ (sujets rattachés + créés)` de la synchro,
     arrondi à 3 décimales ; `null` si la synchro n'a rien rattaché.
4. **La réserve du poste** : `GET /api/radar/hosts/{hostId}/reserve` →
   `{monthlyTokens, consumedThisMonth, remainingThisMonth, perSyncTokens, resetsAt}`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Réserve épuisée avant le tri | lot `DEFERRED` (`RESERVE_EXHAUSTED`), aucun appel au fournisseur | — |
| Réserve épuisée par le tri | lot `DEFERRED`, jetons du tri comptés, aucune écriture | — |
| Réserve mensuelle configurée ≤ 0 ou aberrante | retombe sur le défaut (3 000 000) | — |
| `GET /reserve` sur le poste d'autrui | `not_found` | 404 |
| `GET /reserve` sans droit Teams | `teams_forbidden` | 403 |

---

## Critères d'acceptation

- [ ] Réserve mensuelle épuisée : le lot est `DEFERRED` sans appel, échéance au 1ᵉʳ du mois suivant ;
      les lots suivants du poste aussi ; le brut est conservé.
- [ ] Réserve épuisée par le tri : `DEFERRED`, jetons du tri sur le lot et la synchro, aucune écriture.
- [ ] Plafond par synchro : une synchro qui l'atteint s'arrête, une nouvelle synchro repart.
- [ ] La consommation d'un autre poste (même utilisateur) ou d'un autre mois n'entame pas la réserve.
- [ ] `analysis.costUsd`, `stoppedOnReserve`, `unreadBatches`, `attachmentCorrections`,
      `attachmentCorrectionRate` justes sur un jeu connu (une fusion et une séparation après la synchro,
      une annulée non comptée, une faite après la synchro suivante non comptée).
- [ ] `GET /reserve` rend la réserve du poste ; 404 pour autrui ; 403 sans droit.
- [ ] **Isolation** : la mesure et la réserve d'un poste ne comptent rien d'un autre poste ni de Bob.

---

## Périmètre

### Hors scope (explicite)

- Le droit Vigie, le montant définitif et l'enveloppe de l'essai (F-107 / SF-107-03, SF-107-04), Stripe.
- Le décompte au quota des conversations (volontairement **absent**, cadrage §11).
- L'écran qui dit « synchro arrêtée, réserve épuisée » (F-102) et la vue PO agrégée multi-comptes.

---

## Contraintes de validation

| Champ | Obligatoire | Borne | Règle |
|-------|-------------|-------|-------|
| `app.radar.reserve.monthly-tokens` | Non | [10 000, 1 000 000 000] | défaut 3 000 000 ; hors bornes → défaut |
| `app.radar.reserve.per-sync-tokens` | Non | 0 ou [10 000, 1 000 000 000] | défaut 0 (aucun) ; hors bornes → 0 |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/syncs` (champ `analysis` enrichi) | Oui | droit Teams (inchangé) |
| GET | `/api/radar/hosts/{hostId}/reserve` | Oui | droit Teams (admin bypass) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_syncs` | SELECT | somme mensuelle par poste |
| `radar_analysis_batches` | SELECT / UPDATE | reports, comptes |
| `radar_corrections` | SELECT | fusions / séparations dans la fenêtre de la synchro |

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

- Aucun (F-102).

---

## Plan de test

### Tests unitaires

- [ ] `RadarReservePropertiesTest` — défauts et bornes ; échéance au 1ᵉʳ du mois suivant.
- [ ] `RadarAnalysisReportTest` — taux arrondi, `null` sans rattachement.

### Tests d'intégration

- [ ] `RadarReserveIntegrationTest` (H2, fournisseur factice) — réserve épuisée avant le tri (aucun appel),
      épuisée par le tri, plafond par synchro, autre poste / autre mois non comptés, `GET /reserve`
      (200, 404, 403).
- [ ] `RadarAnalysisMeasureIntegrationTest` — coût, `stoppedOnReserve`, `unreadBatches`, corrections dans
      la fenêtre de la synchro.

### Isolation

- [x] Applicable — consommation et corrections du poste B et de Bob ignorées ; 404 sur `/reserve` d'autrui.

---

## Dépendances

### Subfeatures bloquantes

- SF-101-01 → SF-101-04 — `done`.

### Questions ouvertes impactées

- Aucune. Le montant de la réserve reste **à revalider après l'essai** (F-107), sans question ouverte
  nouvelle.

---

## Préoccupations transversales

- **Plans / limites : oui.** Composants impactés : `RadarReserve` / `ConfiguredRadarReserve` (nouveau
  gate de dépense, **propre au Radar**), `RadarExchangeAnalyzer` (appel du gate), `RadarController`
  (`/reserve`, derrière `TeamsAccessService.requireAccess()` existant). **Aucun** service de quota des
  conversations (`QuotaService`, `EntitlementService`, `TeamsEntitlementService`) n'est appelé ni
  modifié : la synchro ne consomme pas le quota (cadrage §11).
- **Contexte tenant : oui.** Composants impactés : `ConfiguredRadarReserve` (somme par
  `(user_id, host_id)`), `RadarAnalysisReport` (corrections par `(user_id, host_id)`).
- **Auth / Principal : non.** **Navigation : non.**

---

## Notes et décisions

- **Réserve configurée par défaut à 3 M jetons par poste et par mois** : valeur de la grille décidée par
  le PO (F-107), en attendant SF-107-04 qui la portera par l'option Vigie. Réversible par configuration.
- **Unité = jetons bruts** (toutes natures) : c'est l'unité de la grille ; le coût en dollars est relevé
  à côté pour l'essai.
- **Taux de corrections par fenêtre de synchro** plutôt que par preuve : une correction ne dit pas quel
  lot l'a provoquée ; la fenêtre « jusqu'à la synchro suivante » est la mesure honnête la plus simple.
