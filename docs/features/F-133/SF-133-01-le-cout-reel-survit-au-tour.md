# Mini-spec — F-133 / SF-133-01 — Le coût réel survit au tour

## Identifiant
`F-133 / SF-133-01`

## Feature parente
`F-133` — Le coût réel Anthropic : par message, par client, par semaine

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-133-01-cout-reel-survit-au-tour`

---

## Objectif

Faire que le coût fournisseur d'un tour, **aujourd'hui calculé puis jeté**, soit persisté avec les
quatre natures de tokens, le modèle servi et la grille de tarifs utilisée — sans rien changer au
décompte de quota ni aux écrans existants.

---

## Comportement attendu

### Cas nominal

1. Un tour est servi (chat, ask, Atelier, Vigie, Radar, CRA, juge — les 12 appelants de
   `QuotaService.recordUsage`).
2. `recordUsage` calcule le quota **exactement comme aujourd'hui** (aucune modification du décompte).
3. Il calcule en plus le **coût de vérité** du tour, avec la nouvelle grille `app.cost.provider` :
   - si le fournisseur a rapporté son propre coût (`providerCostUsd`, cas des Managed Agents),
     **ce coût fait foi** et `cost_source = PROVIDER` ;
   - sinon `coût = Σ(tokens_nature × tarif_nature_du_modèle) ÷ 1 000 000`, `cost_source = CALCULATED`.
4. Il passe au journal : les **quatre** natures, le coût, le **modèle**, la **version de grille**.
5. `usage_turns` enregistre une ligne complète. Rien n'est affiché : aucun écran ne change.

### Le modèle servi

| Chemin | Source du modèle |
|---|---|
| Chat, ask, CRA, Radar, juge, réunions | `ChatCompletionResult.model` — *« modèle effectivement utilisé, tel que rapporté par le fournisseur »*, déjà présent (`ChatCompletionResult.java:7`), simplement jamais transmis |
| Atelier (boucle maison) | `AgentTurn` **ne porte pas** le modèle : on lui ajoute le modèle rapporté par le fournisseur ; à défaut, le modèle demandé pour la session |
| Managed Agents | coût rapporté directement, le modèle reste informatif |

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Modèle inconnu de la grille de tarifs | coût calculé au tarif du **modèle par défaut** configuré, `pricing_fallback = true` sur la ligne, un `warn` journalisé une fois par modèle et par démarrage. **Jamais d'exception** : un tour servi ne peut pas échouer sur un problème de tarif |
| Modèle non rapporté (`null`) | même traitement, `model` reste `null` sur la ligne |
| Coût fournisseur négatif ou nul | ignoré, on retombe sur le calcul par tokens |
| Écriture du journal en échec | avalée et journalisée en `warn`, comme aujourd'hui (`UsageLedgerService:59`) — le fournisseur a déjà été payé, un relevé perdu est un défaut d'information, un tour en échec serait un défaut de service |
| Tour sans consommation | aucune ligne écrite, inchangé |

---

## Critères d'acceptation

- [ ] `usage_turns` porte `cache_read_tokens`, `cache_write_tokens`, `provider_cost_usd`, `model`, `pricing_version`, `cost_source`, `pricing_fallback`.
- [ ] Les lignes **antérieures** à la migration gardent `NULL` sur les nouvelles colonnes et **ne sont pas** rétro-calculées (les données n'existent pas).
- [ ] Un tour Opus 5 de 10 000 entrée / 5 000 sortie / 40 000 lecture de cache / 8 000 écriture (TTL 1 h) enregistre **0,275 $** : `(10000×5 + 5000×25 + 40000×0,50 + 8000×10) ÷ 1e6`.
- [ ] Un tour dont le fournisseur rapporte 0,80 $ enregistre **0,80 $** et `cost_source = PROVIDER`, quels que soient ses tokens.
- [ ] Le **décompte de quota est inchangé** : à consommation identique, `UsageCounter.billedTokens` vaut exactement ce qu'il valait avant la subfeature (test de non-régression explicite).
- [ ] Le tarif d'**écriture de cache TTL 1 h** vaut **10,00** dans `app.cost.provider` (vérité) et **reste 6,25** dans `app.atelier.agent.cost` (décompte commercial). Les deux valeurs coexistent et sont testées séparément.
- [ ] Les 12 appelants passent le modèle ; aucun ne régresse sur les natures de tokens.
- [ ] Un modèle absent de la grille ne fait **jamais** échouer un tour.
- [ ] Aucune colonne de texte libre n'est ajoutée : `model` est un identifiant de modèle, borné à 64 caractères.
- [ ] Toute lecture de `usage_turns` filtre sur `user_id` (inchangé, re-testé).

---

## Périmètre

### Hors scope (explicite)
- **Aucun affichage.** Ni le terminal, ni les rapports, ni l'admin ne changent (SF-133-02 et 07).
- **Aucune dépense hors tokens** : recherche web et temps de session viennent en SF-133-08.
- Aucune conversion en euros (SF-133-02).
- Aucune modification du quota, des plans, des plafonds de session ou des alertes commerciales.
- Aucun endpoint, aucun composant Angular.
- Pas de reconstitution de l'historique.

---

## Valeurs initiales

| Champ | Valeur à la création | Règle |
|---|---|---|
| `cache_read_tokens` / `cache_write_tokens` | `0` | jamais négatif (`TurnTokens` normalise déjà) |
| `provider_cost_usd` | coût calculé ou rapporté | `NUMERIC(12,6)`, jamais négatif |
| `cost_source` | `CALCULATED` \| `PROVIDER` | posé par le service, jamais par un client |
| `pricing_version` | version de la grille en configuration | ex. `2026-09-20` |
| `pricing_fallback` | `false` | `true` si le modèle était inconnu de la grille |
| `model` | modèle rapporté, ou `null` | 64 caractères max |

Toutes les colonnes sont `updatable = false` : la table reste **append-only**.

---

## Contraintes de validation

| Champ | Obligatoire | Max | Valeurs | Normalisation |
|---|---|---|---|---|
| `model` | Non | 64 | identifiant de modèle | `trim()`, `null` si vide |
| `provider_cost_usd` | Oui | `NUMERIC(12,6)` | ≥ 0 | négatif ⇒ 0 |
| `cost_source` | Oui | 16 | `CALCULATED`, `PROVIDER` | — |
| `pricing_version` | Oui | 32 | date de relevé | — |

---

## Technique

### Endpoints
Aucun.

### Tables impactées

| Table | Opération | Notes |
|---|---|---|
| `usage_turns` | `ALTER` + `INSERT` | 6 colonnes ajoutées, nullable pour l'historique |

### Migration Liquibase
- [x] Oui — `118-usage-turns-cout-reel.xml`

### Classes touchées

| Classe | Changement |
|---|---|
| `UsageTurn` | 6 champs |
| `UsageLedgerService.recordTurn` | signature : `TurnTokens` + coût + modèle + version, au lieu de deux `long` |
| `UsageTurnWriter` | écriture des nouveaux champs |
| `QuotaService.recordUsage:204` | cesse de tronquer en descendant vers le journal |
| **`ProviderPricingProperties`** *(nouveau)* | `app.cost.provider` : grille par modèle, quatre natures, `pricing-version`, modèle par défaut |
| **`ProviderCostCalculator`** *(nouveau)* | coût de vérité d'un tour, par modèle. **Distinct** de `BilledTokensCalculator`, qui garde le tarif commercial |
| `ChatService`, `AskService`, `CraService`, `RadarNewsService`, `RadarDraftService`, `RadarManagerAnswerService`, `JugeIndependantService`, `MeetingCardPromotionService`, `MeetingExploitationService`, `AtelierChatService`, `AtelierSessionService` | passent le modèle |
| `AgentTurn` | porte le modèle rapporté |

### Composants Angular
Aucun.

---

## Plan de test

### Tests unitaires
- [ ] `ProviderCostCalculator` — Opus 5, quatre natures : 0,275 $ sur l'exemple des critères.
- [ ] `ProviderCostCalculator` — Sonnet 5 et Haiku 4.5 : chaque modèle à **son** tarif.
- [ ] `ProviderCostCalculator` — écriture de cache TTL 1 h facturée **10,00**, pas 6,25.
- [ ] `ProviderCostCalculator` — modèle inconnu ⇒ tarif par défaut, `pricing_fallback = true`, aucune exception.
- [ ] `ProviderCostCalculator` — modèle `null`, tokens nuls, valeurs négatives : jamais d'exception, jamais de coût négatif.
- [ ] `BilledTokensCalculator` — **non-régression** : les valeurs d'avant, au tarif commercial inchangé.
- [ ] `UsageLedgerService` — une écriture en échec ne remonte pas ; le `warn` est émis.
- [ ] `QuotaService.recordUsage` — coût rapporté par le fournisseur ⇒ `PROVIDER` ; absent ⇒ `CALCULATED`.

### Tests d'intégration
- [ ] Un tour `/chat` écrit une ligne `usage_turns` complète : natures, coût, modèle, version.
- [ ] Un tour d'Atelier avec cache écrit les natures **séparées**, pas un total d'entrée.
- [ ] Un tour Managed Agents écrit `cost_source = PROVIDER` et le coût rapporté.
- [ ] **Non-régression quota** : à consommation identique, `UsageCounter` (`input`, `output`, `billed`) est strictement identique à la référence d'avant la subfeature.
- [ ] **Non-régression écrans** : `/api/usage/report` (F-16), `/api/usage/by-client` (F-61) et l'admin (SF-61-03) rendent les **mêmes** valeurs qu'avant.
- [ ] Migration jouée sur une base contenant des lignes antérieures : elles survivent avec `NULL`, aucune perte.

### Isolation utilisateur
- [x] Applicable — un utilisateur A ne lit jamais une ligne `usage_turns` de B ; le filtre `user_id`
      est re-testé sur les chemins de lecture existants, qui ne changent pas mais lisent des colonnes nouvelles.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Contexte tenant** | **oui** | `UsageLedgerService`, `UsageTurnWriter`, `UsageTurnRepository`, `UsageByClientService`, `UsageReportService`, `AdminUsageService` — tous relus, filtre `user_id` inchangé |
| **Plans / limites** | **oui** | `QuotaService`, `BilledTokensCalculator`, `AtelierCostProperties`, `EntitlementService` — **aucun changement de comportement**, garanti par les deux tests de non-régression ci-dessus |
| Auth / Principal | non | aucun endpoint, aucun changement d'autorisation |
| Navigation / routing | non | aucun écran |

---

## Estimation

**1 jour.** Une migration, deux classes neuves, une signature élargie, douze appels à compléter.
Le calcul existe déjà ; c'est un travail de plomberie et de non-régression.
