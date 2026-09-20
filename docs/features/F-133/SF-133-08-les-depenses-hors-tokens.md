# Mini-spec — F-133 / SF-133-08 — Les dépenses hors tokens

## Identifiant
`F-133 / SF-133-08`

## Feature parente
`F-133` — Le coût réel Anthropic : par message, par client, par semaine

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-133-08-depenses-hors-tokens`

---

## Objectif

Compter et tarifer les deux dépenses réelles qu'**aucun token ne montre** — la recherche web et le
temps de session — pour que le coût d'un tour cesse de sous-estimer ce qu'on paie.

---

## Pourquoi maintenant, avant l'affichage

SF-133-01 a rendu le coût des tokens exact. Mais la grille officielle facture aussi :

| Dépense | Tarif | État avant cette subfeature |
|---|---|---|
| **Recherche web** | **10 $ / 1 000 requêtes** | comptée **nulle part**. `usage.server_tool_use` n'est lu par aucune ligne du dépôt, alors que `web_search_20260209` est déclaré à chaque tour d'agent (`AnthropicAgentProvider:78`) |
| **Temps de session** (Managed Agents) | **0,08 $ / heure** `running` | **compté** (`UsageCounter.sandboxSeconds`, alimenté par `AtelierSessionService:1063`) mais **jamais tarifé** |

Un coût reconstitué des seuls tokens sous-estime donc systématiquement, et d'autant plus que l'usage
est agentique — le profil exact de l'Atelier et de la Vigie. **Afficher un montant dont on sait
qu'il est faux coûterait plus cher en confiance que d'attendre cette subfeature** : c'est pourquoi
elle passe avant SF-133-02.

---

## Comportement attendu

### Cas nominal

1. Le fournisseur rapporte `usage.server_tool_use.web_search_requests` sur un tour d'agent.
2. `AgentTurn` le porte jusqu'à l'appelant, comme il porte déjà le cache.
3. `recordUsage` reçoit les **extras** du tour : recherches web et secondes de session.
4. Le coût du tour devient : `coût_tokens + recherches × 0,01 $ + secondes × (0,08 ÷ 3600)`.
5. `usage_turns` enregistre les **compteurs** (`web_search_requests`, `sandbox_seconds`) à côté du
   montant, pour qu'un coût élevé puisse être **expliqué** et pas seulement constaté.

### Ce que le coût rapporté par le fournisseur change

Quand le fournisseur rapporte lui-même le coût du tour (Managed Agents), **il inclut déjà** ses
recherches web et son temps de session. Les extras ne sont alors **pas** ajoutés — ils seraient
comptés deux fois. Les compteurs, eux, sont quand même enregistrés : ils expliquent le montant.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| `server_tool_use` absent de la réponse | zéro recherche, aucun coût ajouté — c'est le cas de tous les chemins non agentiques |
| Compteur négatif ou aberrant | ramené à 0, comme pour les tokens |
| Secondes de session négatives (compteur du fournisseur qui recule) | ramenées à 0 : jamais de crédit |
| Tarif d'extra absent de la configuration | retombe sur le défaut documenté, jamais zéro, jamais d'exception |

---

## Critères d'acceptation

- [ ] `usage_turns` porte `web_search_requests (bigint, défaut 0)` et `sandbox_seconds (bigint, défaut 0)`.
- [ ] Un tour avec **3 recherches web** et sans tokens coûte **0,03 $**.
- [ ] Un tour de **900 secondes** de session et sans tokens coûte **0,02 $** (900 × 0,08 ÷ 3600).
- [ ] Un tour de 10 000 entrée / 5 000 sortie sur Opus 5 **avec 2 recherches** coûte **0,195 $** : `(10000×5 + 5000×25) ÷ 1e6 + 2 × 0,01`.
- [ ] Quand le **fournisseur rapporte son coût**, les extras ne sont **pas** ajoutés, mais les compteurs sont enregistrés.
- [ ] `AgentTurn` porte le nombre de recherches web, lu depuis `usage.server_tool_use.web_search_requests`, en non streamé **et** en streamé.
- [ ] Le **décompte de quota reste inchangé** : les extras n'entrent pas dans `billedTokens` (test de non-régression).
- [ ] Aucune recherche web comptée sur les chemins qui n'en déclarent pas (`/chat`, `/ask`).
- [ ] Toute lecture de `usage_turns` filtre sur `user_id` (inchangé, re-testé).

---

## Périmètre

### Hors scope (explicite)
- **Aucun affichage** (SF-133-02 et 07).
- L'exécution de code (`0,05 $/heure` au-delà de 1 550 h offertes **par organisation et par mois**) :
  non modélisable par tour, le seuil étant global et mensuel. À traiter par la réconciliation
  (SF-133-05) si elle se fait.
- Les multiplicateurs Batch (×0,5), `inference_geo: "us"` (×1,1) et fast mode : aucun de ces modes
  n'est utilisé par la passerelle aujourd'hui. À ajouter le jour où l'un le sera.
- Aucun changement du quota commercial.

---

## Technique

### Tables impactées

| Table | Opération | Notes |
|---|---|---|
| `usage_turns` | `ALTER` + `INSERT` | 2 colonnes, `NOT NULL` à 0 |

### Migration Liquibase
- [x] Oui — `119-usage-turns-extras.xml`

### Classes touchées

| Classe | Changement |
|---|---|
| **`TurnExtras`** *(nouveau)* | `webSearchRequests`, `sandboxSeconds` ; normalise les négatifs |
| `ProviderPricingProperties` | `webSearchPerThousand` (10,00), `sessionHour` (0,08) |
| `ProviderCostCalculator` | ajoute le coût des extras au coût des tokens ; **ne l'ajoute pas** quand le fournisseur rapporte son coût |
| `AgentTurn` | porte `webSearchRequests` |
| `AnthropicAgentProvider` | lit `usage.server_tool_use.web_search_requests` (`toTurn` **et** l'accumulateur de flux) |
| `QuotaService.recordUsage` | reçoit les extras |
| `UsageTurn`, `UsageTurnWriter`, `UsageLedgerService` | persistent les compteurs |
| `AtelierChatService`, `AtelierSessionService` | fournissent les extras |

### Composants Angular
Aucun.

---

## Plan de test

### Tests unitaires
- [ ] `ProviderCostCalculator` — 3 recherches sans tokens ⇒ 0,03 $.
- [ ] `ProviderCostCalculator` — 900 s de session ⇒ 0,02 $.
- [ ] `ProviderCostCalculator` — tokens **+** extras cumulés ⇒ 0,195 $ sur l'exemple des critères.
- [ ] `ProviderCostCalculator` — coût rapporté par le fournisseur ⇒ extras **non** ajoutés.
- [ ] `ProviderCostCalculator` — extras négatifs ou nuls ⇒ aucun coût, aucune exception.
- [ ] `TurnExtras` — normalisation des négatifs.
- [ ] `AnthropicAgentProvider` — `server_tool_use` lu en non streamé et en streamé ; absent ⇒ 0.

### Tests d'intégration
- [ ] Un tour avec recherches écrit les compteurs **et** le coût majoré.
- [ ] Un tour Managed Agents écrit les compteurs **sans** double comptage du coût.
- [ ] **Non-régression quota** : `UsageCounter.billedTokens` strictement identique avec ou sans extras.
- [ ] **Non-régression écrans** : F-16 et F-61 rendent les mêmes valeurs.

### Isolation utilisateur
- [x] Applicable — filtre `user_id` inchangé, re-testé.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Plans / limites** | **oui** | `QuotaService`, `BilledTokensCalculator`, `AtelierCostProperties` — les extras n'entrent **pas** dans le décompte commercial, garanti par le test de non-régression |
| **Contexte tenant** | **oui** | `UsageLedgerService`, `UsageTurnWriter`, `UsageTurnRepository` — filtre `user_id` inchangé |
| Auth / Principal | non | aucun endpoint |
| Navigation / routing | non | aucun écran |

---

## Estimation

**1 jour.** Une migration, un record, deux tarifs, un champ de plus sur `AgentTurn` et sa lecture
dans les deux chemins du provider.
