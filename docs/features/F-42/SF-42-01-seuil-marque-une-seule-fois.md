# Mini-spec — [F-42 / SF-01] Le seuil franchi, marqué une seule fois

---

## Identifiant

`F-42 / SF-01`

## Feature parente

`F-42` — Alerte de quota & recharge en un clic

## Statut

`done` — livrée le 2026-09-07

## Date de création

2026-09-07

## Branche Git

`feat/SF-42-01-seuil-alerte-quota`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Poser, **une seule fois par période et par utilisateur**, la marque « seuil de consommation franchi »
sur le compteur d'usage de la période, et l'exposer à l'application via `GET /usage/alert` avec le
pack de recharge recommandé — sans jamais faire échouer l'appel en cours.

---

## Contexte

`QuotaService.recordUsage(userId, in, out)` charge déjà — ou crée — l'unique ligne
`usage_counters(user_id, period_start)` de l'utilisateur, l'incrémente et la sauvegarde. C'est le
seul endroit du système où l'on sait, juste après un appel, ce que l'utilisateur a consommé sur sa
période. C'est donc là que le seuil se juge, et c'est **sur cette même ligne** que la marque se pose :
aucune lecture supplémentaire, aucune table supplémentaire, et le passage au mois suivant crée une
nouvelle ligne qui ré-arme naturellement l'alerte.

Les quatre chemins servis (`ChatService` ×2, `AskService`, `AtelierChatService`,
`AtelierSessionService`) appellent tous `recordUsage` : poser l'évaluation là plutôt qu'aux cinq
appelants garantit qu'un chemin ajouté demain hérite de l'alerte au lieu de l'oublier.

---

## Comportement attendu

### Cas nominal

1. Un appel fournisseur se termine ; l'appelant appelle `quotaService.recordUsage(userId, in, out)`.
2. `recordUsage` charge/crée la ligne de période, ajoute les tokens consommés.
3. **Avant** la sauvegarde, il demande à `QuotaAlertService.evaluateAfterUsage(userId, counter)` de
   juger le seuil. Ce service :
   - résout le quota **effectif** de la période = entitlement d'abonnement + `bonus_tokens` rachetés ;
   - si ce quota est `<= 0`, **ne fait rien** (offre BYOK ou abonnement sans allocation : il n'y a
     pas de seuil à franchir) ;
   - si `quota_alert_raised_at` est **déjà posé**, **ne fait rien** — c'est la garantie « une seule
     fois par période et par utilisateur » ;
   - sinon, si `total_tokens / quota >= app.quota.alert.threshold`, pose
     `quota_alert_raised_at = now()` sur la ligne **en mémoire** (elle est sauvegardée juste après,
     dans la même écriture — aucun `UPDATE` supplémentaire).
4. `recordUsage` sauvegarde la ligne. L'appel en cours n'est **jamais** affecté.
5. L'application interroge `GET /api/usage/alert`. Tant que `quota_alert_raised_at` est posé et que
   `quota_alert_dismissed_at` ne l'est pas, la réponse porte `raised: true`, les chiffres de la
   période, le seuil configuré et le **pack de recharge recommandé** (`app.quota.alert.top-up-pack`,
   défaut `STANDARD`) résolu depuis `TopUpCatalog`.
6. L'utilisateur écarte l'alerte : `POST /api/usage/alert/dismiss` pose
   `quota_alert_dismissed_at = now()`. `GET /usage/alert` rend `raised: false` jusqu'à la fin de la
   période, **même si la consommation continue de monter**.

### L'alerte ne bloque jamais

`recordUsage` encapsule l'appel à `evaluateAfterUsage` dans un `try/catch` : toute exception
inattendue de l'évaluation est **journalisée en `warn` et avalée**, et la consommation est
sauvegardée quand même. Une alerte manquée est un défaut d'information ; une consommation perdue ou
un appel en échec seraient un défaut de facturation et d'expérience. L'ordre de gravité est explicite.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Aucun JWT sur `GET /usage/alert` ou `POST /usage/alert/dismiss` | Rejet par la chaîne de sécurité (`anyRequest().authenticated()`) | 401 |
| Aucune ligne de période pour l'utilisateur (aucune consommation) | `raised: false`, chiffres à zéro, aucune écriture | 200 |
| Seuil franchi puis alerte écartée, consommation qui continue de monter | `raised: false` — l'alerte n'est **pas** ré-émise sur la période | 200 |
| `POST /dismiss` alors qu'aucune alerte n'est levée | Aucune écriture, réponse vide | 204 |
| `app.quota.alert.threshold` hors de `]0, 1]` (0, négatif, > 1, absent) | Repli sur le défaut `0.8` au binding | — (démarrage) |
| `app.quota.alert.top-up-pack` inconnu du catalogue | `raised` reste correct, `topUp` vaut `null` — l'alerte informe, seul le bouton de recharge disparaît | 200 |
| Quota effectif nul (BYOK, abonnement résilié) | Aucune marque posée, `raised: false` | 200 |
| Exception inattendue pendant l'évaluation du seuil | Journalisée en `warn`, consommation sauvegardée, appel non affecté | — |

---

## Critères d'acceptation

- [ ] **Unicité** : trente appels consécutifs au-dessus du seuil sur la même période posent
      `quota_alert_raised_at` **une seule fois**, et l'horodatage du premier franchissement n'est
      jamais réécrit.
- [ ] **Non-blocage** : si `QuotaAlertService` lève, `recordUsage` sauvegarde quand même la
      consommation et ne propage aucune exception.
- [ ] Le seuil se juge sur le quota **effectif** : un top-up qui double le quota fait repasser le
      ratio sous le seuil et n'arme donc pas l'alerte tant que la consommation n'y revient pas.
- [ ] Un quota effectif `<= 0` (BYOK, abonnement résilié) ne pose **jamais** de marque.
- [ ] `GET /usage/alert` rend `raised: true` au-dessus du seuil, avec `usedTokens`, `quotaTokens`,
      `remainingTokens`, `thresholdPercent`, `usedPercent`, `periodEnd` et le pack recommandé.
- [ ] `POST /usage/alert/dismiss` rend `204` et fait passer `raised` à `false` définitivement pour
      la période.
- [ ] **Isolation `user_id`** : l'alerte d'Alice n'est jamais visible par Bob, et le `dismiss` de
      Bob n'efface pas l'alerte d'Alice. L'identité vient **exclusivement** du `CurrentUser` (JWT) —
      aucun identifiant utilisateur n'est accepté en paramètre.
- [ ] Le seuil et le pack recommandé sont des **variables d'environnement** avec défaut dans
      `application.yml` (`APP_QUOTA_ALERT_THRESHOLD=0.8`, `APP_QUOTA_ALERT_TOPUP_PACK=STANDARD`).
- [ ] La migration ajoute deux colonnes **nullables** : aucune ligne existante n'est modifiée, et un
      compteur antérieur se relit avec `raised: false`.
- [ ] `QuotaExceededException` et le blocage à la limite sont **inchangés** — un test le prouve.

---

## Périmètre

### Hors scope (explicite)

- La **bannière** et le bouton de recharge côté Angular → SF-42-02.
- Le **dépassement facturé** (overage) : `OQ-08` reste ouverte, cette subfeature ne la tranche pas.
- Tout **envoi d'e-mail** : l'alerte vit dans l'application.
- Toute modification des quotas, du catalogue de packs, ou du comportement de blocage à la limite.
- Le **ré-armement** de l'alerte après un rachat de tokens (voir §Notes et décisions).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `usage_counters.quota_alert_raised_at` | `NULL` | Aucune alerte n'est levée tant que le seuil n'est pas franchi. Posé **une seule fois** par ligne. |
| `usage_counters.quota_alert_dismissed_at` | `NULL` | Posé par `POST /usage/alert/dismiss`, jamais effacé sur la période. |

Comportements à la création :
- La ligne `usage_counters` continue d'être créée par `recordUsage` / `creditBonusTokens` avec
  `user_id` = utilisateur du contexte de sécurité et `period_start` = premier jour du mois UTC.
- Une **nouvelle période** = une **nouvelle ligne** : les deux colonnes y repartent à `NULL`, donc
  l'alerte est ré-armée chaque mois sans code de remise à zéro.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `app.quota.alert.threshold` | Non (défaut `0.8`) | — | `double` dans `]0, 1]` ; hors bornes ou absent → défaut `0.8` | — | — |
| `app.quota.alert.top-up-pack` | Non (défaut `STANDARD`) | — | code de `TopUpCatalog` (`DAY`, `STANDARD`) ; inconnu → aucun pack recommandé | — | `trim()`, résolution insensible à la casse (`TopUpCatalog.find`) |
| `quota_alert_raised_at` | Non | — | `timestamptz` nullable, posé une seule fois | — | horloge `Clock` UTC injectée |
| `quota_alert_dismissed_at` | Non | — | `timestamptz` nullable | — | horloge `Clock` UTC injectée |

Notes :
- Un seuil de `1.0` est légal : il signifie « préviens-moi quand le quota est atteint ». Un seuil de
  `0` serait absurde (alerte dès le premier jeton) et retombe sur le défaut.
- Le pourcentage exposé (`thresholdPercent`, `usedPercent`) est un entier arrondi, calculé côté
  serveur : l'affichage ne recalcule rien.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/usage/alert` | Oui | USER (utilisateur authentifié) |
| POST | `/api/usage/alert/dismiss` | Oui | USER (utilisateur authentifié) |

Aucun identifiant de ressource n'est accepté : l'identité vient du `CurrentUser` (JWT). Il n'y a donc
pas de `requireOwned` à poser — il n'y a rien à posséder qu'un identifiant client pourrait désigner.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `usage_counters` | ALTER (2 colonnes), SELECT, UPDATE | Filtre `user_id` + `period_start` sur tous les accès (méthodes existantes du repository) |

### Migration Liquibase

- [x] Oui — `058-usage-counters-quota-alert.xml`
- [ ] Non applicable

### Composants Angular (si applicable)

Aucun — SF-42-02.

### Classes créées / modifiées

| Classe | Nature |
|--------|--------|
| `quota/QuotaAlertProperties` | **Créée** — `@ConfigurationProperties("app.quota.alert")`, seuil + pack recommandé |
| `quota/QuotaAlertService` | **Créée** — évaluation du seuil, lecture de l'alerte, écartement |
| `quota/QuotaAlert` | **Créée** — record métier interne (raised, chiffres, seuil, pack) |
| `quota/dto/QuotaAlertResponse` | **Créée** — projection d'API |
| `quota/UsageCounter` | Modifiée — 2 champs `OffsetDateTime` nullables |
| `quota/QuotaService` | Modifiée — appel non bloquant à l'évaluation dans `recordUsage` |
| `quota/UsageController` | Modifiée — 2 endpoints |
| `quota/QuotaConfig` | Modifiée — `@EnableConfigurationProperties` + `QuotaAlertProperties` |

---

## Plan de test

### Tests unitaires

- [ ] `QuotaAlertServiceTest` — au-dessus du seuil et marque absente : `quota_alert_raised_at` posé.
- [ ] `QuotaAlertServiceTest` — **deuxième** évaluation au-dessus du seuil : l'horodatage n'est pas
      réécrit (unicité).
- [ ] `QuotaAlertServiceTest` — sous le seuil : aucune marque.
- [ ] `QuotaAlertServiceTest` — exactement au seuil : marque posée (comparaison `>=`).
- [ ] `QuotaAlertServiceTest` — quota effectif nul (BYOK / résilié) : aucune marque.
- [ ] `QuotaAlertServiceTest` — le quota effectif inclut les `bonus_tokens` : 850 k consommés sur
      1 M + 1 M rachetés ⇒ ratio 42,5 % ⇒ aucune marque.
- [ ] `QuotaAlertServiceTest` — `pendingAlert` : `raised` vrai si posé et non écarté, faux si écarté,
      faux si aucune ligne de période.
- [ ] `QuotaAlertServiceTest` — `dismiss` sans alerte levée : aucune écriture.
- [ ] `QuotaAlertServiceTest` — pack recommandé inconnu du catalogue : `topUp` nul, `raised` correct.
- [ ] `QuotaServiceTest` — `recordUsage` sauvegarde la consommation **même si** l'évaluation lève.
- [ ] `QuotaServiceTest` — `recordUsage` ne propage pas l'exception de l'évaluation.
- [ ] `QuotaAlertPropertiesTest` — défauts et repli sur seuil absent / `0` / négatif / `> 1`.

### Tests d'intégration

- [ ] `GET /api/usage/alert` → 401 sans JWT.
- [ ] `POST /api/usage/alert/dismiss` → 401 sans JWT.
- [ ] Consommation portée au-dessus du seuil via le chat stubé → `GET /usage/alert` rend
      `raised: true` avec les bons chiffres et le pack `STANDARD`.
- [ ] Appels supplémentaires au-dessus du seuil → l'horodatage en base reste **le premier**.
- [ ] `POST /usage/alert/dismiss` → 204, puis `GET /usage/alert` rend `raised: false`.
- [ ] Consommation sous le seuil → `raised: false`.
- [ ] Le blocage `402` à la limite du quota est **inchangé** (non-régression F-10).

### Isolation utilisateur

- [x] Applicable — tests :
  - Alice franchit le seuil, Bob non : `GET /usage/alert` de Bob rend `raised: false`.
  - Bob appelle `POST /usage/alert/dismiss` : l'alerte d'Alice reste `raised: true`.
  - Le seuil est calculé **par utilisateur** (quota et consommation lus sur la ligne de l'utilisateur
    courant uniquement), jamais globalement.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|--------------|--------|---------------------|
| **Auth / Principal** | Non | Aucun changement du `Principal`, du JWT ni de la chaîne de sécurité. Les deux nouvelles routes tombent sous `anyRequest().authenticated()` déjà en place. |
| **Contexte tenant** | Non | Le tenant reste résolu par `CurrentUser.requireId()`, inchangé. Aucun nouveau moyen de résoudre `user_id`. |
| **Plans / limites** | **Oui** | Un seuil est ajouté **à côté** des limites existantes, sans en changer aucune. Composants qui appellent les services de limites, tous vérifiés : `ChatService` (2 appels `assertWithinQuota` + 2 `recordUsage`), `AskService` (1 + 1), `AtelierChatService` (1 + 1), `AtelierSessionService` (1 + 1), `QuotaService.assertWithinSandboxLimit`, `TopUpService`/`creditBonusTokens` (crédit de bonus). **Aucun gate n'est modifié** : `assertWithinQuota` et `QuotaExceededException` sont intouchés ; seul `recordUsage` gagne une évaluation **non bloquante** après incrément. Un test de non-régression fige le blocage `402` à la limite. |
| **Navigation / routing** | Non | Aucune route Angular, aucun guard, aucune redirection (SF-42-02 n'ajoute pas de route non plus : une bannière dans la coquille existante). |

---

## Dépendances

### Subfeatures bloquantes

- F-10 (compteurs d'usage, `usage_counters`) — `done`
- F-21 / SF-21-02 (`TopUpCatalog`, `POST /billing/topup/checkout`) — `done`

### Questions ouvertes impactées

- [ ] `OQ-08` — dépassement facturé : **non tranchée**, et volontairement laissée ouverte. F-42
      prévient du franchissement d'un seuil ; elle ne facture aucun dépassement et ne change pas le
      blocage à la limite.

---

## Notes et décisions

**D1 — La marque vit sur `usage_counters`, pas dans une table ni en mémoire.** La ligne
`(user_id, period_start)` *est* déjà l'unité « un utilisateur, une période », elle est déjà unique en
base et déjà chargée puis sauvegardée par `recordUsage`. La marque coûte donc zéro lecture et zéro
écriture supplémentaires, et le passage au mois suivant ré-arme l'alerte sans une ligne de code. Une
marque en mémoire aurait été perdue à chaque redéploiement et aurait divergé entre les deux replicas
(`PROJECT.md` §13.9 — stateless) : l'alerte serait repartie à chaque roulement de pod.

**D2 — Deux colonnes, parce que deux faits distincts.** `raised_at` dit que le seuil a été franchi
(unicité de l'émission) ; `dismissed_at` dit que l'utilisateur l'a lue (droit de la fermer). Fusionner
les deux aurait forcé à choisir entre une alerte impossible à fermer et une alerte ré-émise.

**D3 — L'évaluation est faite après l'incrément, avant la sauvegarde.** Elle mute la ligne déjà en
mémoire ; la sauvegarde qui suit persiste les deux faits (consommation et marque) en une écriture.

**D4 — Arbitrage à revoir par le PO : pas de ré-armement après un rachat.** La règle imposée est
« une seule fois par période et par utilisateur », appliquée à la lettre. Conséquence assumée : un
utilisateur alerté à 80 % de 1 M, qui rachète 1 M, ne sera **pas** ré-alerté à 80 % des 2 M. Ré-armer
l'alerte lors d'un `creditBonusTokens` serait défendable — un rachat est une action délibérée, pas du
spam — mais c'est une décision commerciale, pas technique : elle est remontée au PO plutôt que prise
en silence.

**D5 — Le pack recommandé est configurable.** `STANDARD` (1 M) par défaut plutôt que `DAY` (200 k) :
l'alerte se déclenche à 80 % d'un quota mensuel, un pack de 200 k ne tiendrait souvent pas jusqu'à la
fin du mois. Valeur commerciale réversible par environnement, comme le seuil.
