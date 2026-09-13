# Mini-spec — [F-107 / SF-107-04] L'essai Vigie et la réserve de synchro

---

## Identifiant

`F-107 / SF-107-04`

## Feature parente

`F-107` — L'offre par espace : la plateforme se paie, en BYOK aussi
(cadrage validé : `CADRAGE-F-107-l-offre-par-espace.md`, §3, §5, §9)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-107-04-essai-vigie-reserve`

---

## Objectif

Un code d'accès peut ouvrir **la Vigie deux semaines** avec une **réserve d'essai de 3 M jetons**, la
réserve de synchro suit le droit Vigie (3 M par client suivi et par mois, **première synchro hors
réserve**), et le PO lit le **coût réel de chaque synchro d'essai**.

---

## Contexte

- F-62 : un code d'accès ouvre un droit à durée limitée (24 h), aujourd'hui **tous les espaces** (la
  Forge et, depuis F-89/F-106, la Vigie) — il n'a pas d'espace.
- F-101 SF-101-05 : `ConfiguredRadarReserve`, 3 M jetons **par poste et par mois**, sans lien avec le
  droit ; la première synchro (30 jours d'historique) est décomptée comme les autres.
- Cadrage §9 : essai Vigie 2 semaines par code, réserve d'essai 3 M, première synchro offerte ; première
  synchro hors réserve une fois par client ; réserve revalidée après l'essai (mesure × 1,5).

---

## Comportement attendu

### Cas nominal

1. **Un code a un espace** (`access_codes.granted_space`, migration `094`) :
   - émission : `POST /api/admin/access-codes` accepte `space` = `FORGE` (défaut) ou `VIGIE` ;
   - `FORGE` : durée `app.access-code.duration-hours` (24 h) — **n'ouvre que la Forge** ;
   - `VIGIE` : durée `app.access-code.vigie-trial-days` (**14 jours**) — **n'ouvre que la Vigie** ;
   - codes existants (`granted_space` nul) : **ouvrent les deux espaces**, comme avant (rien ne change
     pour un code déjà remis).
2. **Droit** : `AccessGrantService.isGrantedWithGrace(userId, space)` ne retient que les codes vivants de
   cet espace (ou sans espace) ; `SpaceEntitlementService` le lit par espace.
3. **Consommation** : pas de cumul **dans un même espace** (409 `access_code_already_granted`) ; un essai
   Vigie peut coexister avec un code Forge. La réponse et `GET /api/access-code/grant` portent `space`.
4. **La réserve de synchro suit le droit Vigie** (`VigieRadarReserve` remplace `ConfiguredRadarReserve`) :
   - droit par plan, option ou rôle administrateur → **3 M par client suivi et par mois civil** (clé
     existante `app.radar.reserve.monthly-tokens`), renouvelée le 1ᵉʳ ;
   - droit **par essai Vigie seulement** → **réserve d'essai** `app.radar.reserve.trial-tokens` (**3 M**)
     pour **tout le compte** sur la durée de l'essai (depuis la consommation du code), sans
     renouvellement ;
   - aucun droit Vigie → réserve nulle (l'analyse s'arrête proprement, comme aujourd'hui) ;
   - le plafond par synchro facultatif reste appliqué.
5. **Première synchro hors réserve** (`radar_syncs.reserve_exempt`, migration `094`) : la synchro lancée
   quand le poste n'a encore **aucune synchro réussie ou partielle** est marquée exemptée ; sa
   consommation n'est **jamais** décomptée d'aucune réserve, et elle n'est pas arrêtée par une réserve
   épuisée. `GET /reserve` du Radar dit si la réserve est celle de l'essai (`trial`).
6. **Relevé pour le PO** : `GET /api/admin/vigie-trials` (ADMIN) — pour chaque code Vigie consommé :
   libellé, compte, début et fin d'essai, état ; pour chaque synchro de ce compte pendant l'essai :
   poste, date, statut, première synchro, jetons consommés, **coût estimé** (tarifs configurés, même calcul
   que `GET /syncs`) ; totaux. Écran d'administration des codes : choix de l'espace à l'émission, espace
   affiché dans la liste, section « Mesure des essais Vigie ».
7. **Écran d'abonnement** : l'accès offert en cours dit son espace (« Essai de la Vigie » → `/vigie`,
   « Accès Forge offert » → `/atelier`).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `space` inconnu à l'émission | `invalid_request` | 400 |
| Émission par un non-ADMIN | refus existant | 403 |
| Consommation d'un code Vigie alors qu'un essai Vigie est en cours | `access_code_already_granted` | 409 |
| `GET /admin/vigie-trials` par un non-ADMIN | refus | 403 |
| Code Forge consommé : accès à la Vigie | refusé (sans option ni plan) | 403 |
| Essai Vigie : réserve d'essai épuisée | analyse reportée `RESERVE_EXHAUSTED`, sans date de reprise | — |
| Non authentifié | 401 | 401 |

---

## Critères d'acceptation

- [ ] CA1 — code `VIGIE` consommé : Vigie ouverte 14 jours, Forge refusée ; code `FORGE` : l'inverse, 24 h.
- [ ] CA2 — code sans espace (existant) : les deux espaces ouverts (non-régression F-62).
- [ ] CA3 — Forge en cours + consommation d'un code Vigie : accepté ; Vigie en cours + second Vigie : 409.
- [ ] CA4 — réserve, droit par option : 3 M par poste et par mois ; par essai seul : 3 M pour le compte
      depuis la consommation, `retryAt` nul ; sans droit : épuisée.
- [ ] CA5 — première synchro d'un poste marquée exemptée ; sa consommation n'entre dans aucune somme ;
      une synchro exemptée n'est pas arrêtée par une réserve épuisée ; la seconde n'est pas exemptée.
- [ ] CA6 — `GET /admin/vigie-trials` : synchros de l'essai avec jetons et coût, 403 pour un USER.
- [ ] CA7 — isolation : réserve et relevé filtrés `user_id` (+ `host_id` par synchro) ; le relevé
      n'est lisible que par l'ADMIN.
- [ ] CA8 — écrans : espace à l'émission et dans la liste ; mesure des essais ; bandeau d'accès offert
      selon l'espace ; aucune couleur hors charte.

---

## Périmètre

### Hors scope (explicite)

- Réserve définitive (mesure × 1,5) : décision PO après l'essai.
- Supplément par client par espace et jetons de synchro apportés par un client en plus (SF-107-05).
- Recharge de réserve de synchro (« complétée par une recharge ») : aucun pack de synchro n'existe ; à
  cadrer (price à créer) — noté en risque résiduel.
- Écrans du Radar (F-102 / F-103).
- Toute action Stripe.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `app.access-code.vigie-trial-days` | `14` | §9 ; ≤ 0 → 14 |
| `app.radar.reserve.trial-tokens` | `3000000` | §9 ; hors bornes → 3 M |
| `app.radar.reserve.monthly-tokens` | `3000000` | inchangé |

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| `space` (émission) | absent → `FORGE` ; `FORGE` ou `VIGIE`, casse indifférente ; autre → 400 |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/admin/access-codes` | Oui | ADMIN — champ `space` |
| GET | `/api/admin/access-codes` | Oui | ADMIN — champ `space` |
| GET | `/api/access-code/grant` | Oui | USER — champ `space` |
| GET | `/api/admin/vigie-trials` | Oui | ADMIN |
| GET | `/api/radar/hosts/{hostId}/reserve` | Oui | USER — champ `trial` |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `access_codes` | ALTER + INSERT/SELECT | `granted_space varchar(16)` nullable |
| `radar_syncs` | ALTER + INSERT/SELECT | `reserve_exempt boolean default false not null` |
| `subscriptions` | SELECT | droit Vigie |

### Migration Liquibase

- [x] `094-access-code-space-and-first-sync.xml` — deux colonnes additives ; rollback : drop. Aucune
      ligne existante modifiée (codes sans espace = tous espaces, synchros existantes non exemptées).

### Composants impactés

- Backend : `AccessCode`, `AccessCodeRepository`, `AccessCodeService`, `AccessCodeProperties`,
  `AccessGrant`, `AccessGrantService`, DTO d'accès, `SpaceEntitlementService`, `RadarSync`,
  `RadarSyncRepository`, `RadarSyncLauncher`, `RadarReserve` (+ `ReserveView.trial`),
  `VigieRadarReserve` (nouveau, remplace `ConfiguredRadarReserve`), `RadarReserveProperties`,
  `RadarTrialMeasureService` + `VigieTrialAdminController` (nouveaux), `application.yml`.
- Frontend : `access-code-dialog` (espace), `access-codes.component` (espace, mesure des essais),
  `access-code-admin.{models,service}`, `access-code.models`, `billing.component` (bandeau).

### Préoccupations transversales

- [x] **Plans / limites** — droit offert par espace et réserve liée au droit. Lecteurs du droit offert :
  `SpaceEntitlementService` (seul) → `AtelierAccessService`, `TeamsAccessService`, `TeamsToolCatalog`,
  `RunnerTeamsMomentController`, `RadarController`, `RadarSyncPlanner`, `RadarExchangeAnalyzer`,
  `AtelierOptionService`, `VigieOptionService` ; `AccessCodeService.redeem` / `currentGrant`
  (`AccessCodeController`, `billing.component`). Lecteurs de la réserve : `RadarExchangeAnalyzer`,
  `RadarController` (`/reserve`). Quota des conversations : **non touché**.
- [ ] Auth / Principal — non.
- [ ] Contexte tenant — non.
- [x] **Navigation** — le bandeau d'accès offert pointe vers `/vigie` pour un essai Vigie (route
  existante, gardée par le droit Vigie) ; `/atelier` inchangé pour la Forge.

---

## Plan de test

### Tests unitaires

- [ ] `AccessGrantServiceTest` — par espace : code Vigie, code Forge, code sans espace ; grâce.
- [ ] `AccessCodeServiceTest` — émission Vigie (336 h, espace figé), Forge par défaut ; cumul par espace.
- [ ] `SpaceEntitlementServiceTest` — l'accès offert est lu pour l'espace demandé.
- [ ] `VigieRadarReserveTest` — option (mensuel, par poste), essai (compte, sans renouvellement), aucun
      droit, synchro exemptée, plafond par synchro.
- [ ] `RadarReserveProperties` — défaut d'essai.

### Tests d'intégration

- [ ] `AccessCodeApiIntegrationTest` (existant, étendu) — émission `space=VIGIE`, 400 sur espace inconnu,
      consommation, `grant.space`.
- [ ] `VigieTrialAdminApiIntegrationTest` — 403 USER, relevé d'un essai avec synchros exemptée et normale.
- [ ] Radar : première synchro exemptée (`RadarSyncLauncher`), `GET /reserve` `trial`.

### Frontend

- [ ] `access-code-dialog` — espace Vigie transmis ; `access-codes.component` — mesure affichée ;
      `billing.component` — bandeau « Essai de la Vigie ».

### Isolation

- [x] Applicable — réserve et sommes filtrées `user_id` (+ `host_id`) ; relevé réservé à l'ADMIN
      (`AdminService.assertAdmin`), test 403.

---

## Dépendances

### Subfeatures bloquantes

- SF-107-02 et SF-107-03 (livrées).

### Questions ouvertes impactées

- OQ-16 : aucune ; la réserve définitive reste une décision PO après l'essai (cadrage §9).

---

## Notes et décisions

- **Codes existants = tous espaces** : un code déjà remis garde exactement ce qu'il ouvrait.
- **Réserve d'essai par compte** et non par poste : l'essai mesure un client suivi ; par poste, un essai à
  cinq postes offrirait 15 M.
- **Première synchro** : exemptée tant que le poste n'a aucune synchro réussie ou partielle ; une purge du
  Radar (clôture, retrait de la Vigie) rouvre l'exemption à la réactivation — noté en risque résiduel.
