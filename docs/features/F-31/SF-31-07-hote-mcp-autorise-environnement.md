# Mini-spec — [F-31 / SF-31-07] L'hôte du serveur MCP autorisé par la politique réseau de l'environnement

---

## Identifiant

`F-31 / SF-31-07`

## Feature parente

`F-31` — Atelier sur dépôt Git. Correctif de **SF-31-05** (serveur MCP GitHub), qui a déclaré le
serveur sans étendre la politique réseau de l'environnement Managed Agents.

## Statut

`ready`

## Date de création

2026-08-29

## Branche Git

`fix/SF-31-07-hote-mcp-autorise-environnement`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire en sorte que l'environnement provisionné par l'Atelier **autorise l'hôte du serveur MCP** qu'il
va lui-même déclarer, au lieu de le laisser bloquer par sa propre politique réseau.

---

## Contexte

### Constat de production (2026-08-29, mesuré)

Premier usage réel d'un projet Git : le workspace se crée (`ftounga/scrm`, `master`), le jeton est
enregistré, le vault MCP est créé — puis l'ouverture de session échoue.

```
15:18:22  WARN  AnthropicManagedAgentProvider : Appel au fournisseur d'agents en échec
                (création de la session) : HTTP 400, type invalid_request_error
```

Le message du fournisseur n'est pas journalisé (choix de SF-30-08 : statut et type seuls). L'appel a
donc été **rejoué par élimination** contre l'API, avec la clé du cluster :

| Sonde | Corps | Résultat |
|---|---|---|
| 1 | agent nu + `environment_id` | `200` |
| 2 | + surcharge modèle (`claude-opus-5` / `xhigh`) | `200` |
| 3 | **+ serveur MCP GitHub** (`tools` + `mcp_servers` + `vault_ids`) | **`400`** |
| 4 | + `budget` | `400` (même cause) |

```
MCP server host(s) blocked by environment network policy: "github" (api.githubcopilot.com).
Add these hosts to the environment's allowed_hosts, or set allow_mcp_servers=true.
```

L'environnement `env_01HKvqAGAujCKcTkUSCu3Pdu`, provisionné le **2026-07-11**, portait :

```json
"networking": { "type": "limited", "allow_mcp_servers": false, "allowed_hosts": [] }
```

### Cause racine

`AnthropicManagedAgentProvider.createEnvironment()` pose `type: limited` et
`allow_package_managers`, et **rien d'autre**. SF-31-05 (2026-08-26) a ajouté la déclaration du
serveur MCP GitHub côté **session**, sans que la politique réseau de l'**environnement** suive —
deux endroits du code, une seule intention, et aucune vérification que le second autorise ce que le
premier déclare.

Le défaut ne pouvait apparaître qu'au premier usage réel : les tests de SF-31-05 portent sur le corps
JSON **envoyé**, pas sur la réponse d'un environnement réel, et l'environnement de production n'avait
plus été touché depuis sa création (`updated_at` = `created_at`).

### Portée

`AtelierSessionService.openGitSession()` attache le MCP **dès qu'un vault existe**, et le vault est
créé automatiquement à la première session Git. **Toute session sur un projet Git échouait donc**,
quelle que soit la commande demandée. Les projets d'archive ne sont pas touchés (sondes 1 et 2).

### Correctif déjà appliqué en production

L'environnement existant a été mis à jour hors code, l'API l'autorisant
(`POST /v1/environments/{id}` → `200` ; `PATCH` → `405`) :

```json
"networking": { "type": "limited", "allow_package_managers": true,
                "allowed_hosts": ["api.githubcopilot.com"] }
```

Vérifié ensuite : la création de session avec MCP **et** budget répond `200`. La politique retenue est
`allowed_hosts` ciblé plutôt que `allow_mcp_servers: true` — **arbitrage de l'owner du 2026-08-29**,
au titre du moindre privilège et de la cohérence avec `networking: limited`.

Cette subfeature ne rejoue pas ce geste : elle empêche qu'une **prochaine** installation le
redemande.

---

## Comportement attendu

### Cas nominal

1. Au bootstrap, l'environnement est créé avec `allowed_hosts` contenant l'**hôte du serveur MCP
   configuré** — dérivé de `app.git.mcp-server-url`, jamais réécrit en dur.
2. La session déclarant ce serveur MCP est acceptée par le fournisseur.
3. Un déploiement dont la configuration ne change pas produit exactement l'environnement d'avant,
   plus cet hôte.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `app.git.mcp-server-url` illisible (URL malformée, sans hôte) | Aucun hôte autorisé, `allowed_hosts` absent du corps : l'environnement reste restrictif, le bootstrap **n'échoue pas** — un environnement non provisionné serait pire qu'un environnement sans MCP |
| Aucun hôte à autoriser (liste vide) | `allowed_hosts` **absent** du corps : le corps envoyé est strictement celui d'avant SF-31-07, non-régression testée |
| Environnement **déjà provisionné** | Aucun appel : `ensureBootstrapped()` renvoie la config en base sans jamais la comparer. Le correctif ne répare pas une installation existante — voir « Hors scope » |

---

## Critères d'acceptation

- [ ] `createEnvironment` pose `networking.allowed_hosts` avec l'hôte du serveur MCP configuré
- [ ] L'hôte est **dérivé** de `app.git.mcp-server-url`, jamais écrit en dur
- [ ] Une URL MCP malformée ne fait pas échouer le bootstrap : aucun hôte autorisé, environnement créé
- [ ] Liste d'hôtes vide → `allowed_hosts` absent du corps (corps identique à celui d'avant)
- [ ] Le corps envoyé est vérifié sur un serveur HTTP de test, pas seulement sur un double
- [ ] Suite backend verte

---

## Périmètre

### Hors scope (explicite)

- **Réconcilier un environnement déjà provisionné.** `ensureBootstrapped()` ne compare jamais la
  config en base à la config voulue — limite connue et déjà relevée pour le modèle en SF-28-17. La
  corriger touche le cycle de vie du provisionnement pour toutes ses propriétés (nom, paquets,
  modèle) et mérite sa propre subfeature. L'environnement de production a été mis à jour à la main,
  geste tracé ci-dessus.
- `allow_mcp_servers: true` — écarté par l'arbitrage de l'owner.
- Journaliser le message d'erreur du fournisseur, qui aurait donné la cause sans rejouer l'appel :
  SF-30-08 a délibérément exclu le corps brut des journaux. Revenir dessus est une décision de
  sécurité distincte, à instruire pour elle-même.

---

## Valeurs initiales

Aucune donnée créée. La seule valeur nouvelle est l'hôte dérivé de `app.git.mcp-server-url`, dont le
défaut reste `https://api.githubcopilot.com/mcp/` → `api.githubcopilot.com`.

---

## Contraintes de validation

| Champ | Contrainte |
|---|---|
| Hôte MCP | Dérivé par `URI.create(url).getHost()` ; `null` ou vide ⇒ aucun hôte autorisé (aucune exception propagée) |
| `allowed_hosts` | Absent du corps si la liste est vide ; sinon liste de chaînes, sans doublon |

---

## Technique

### Endpoint(s)

Aucun endpoint de la Gateway. Un seul appel fournisseur change : `POST /v1/environments`.

### Tables impactées

Aucune.

### Migration Liquibase

Aucune.

### Composants Angular (si applicable)

Aucun — rien à l'écran.

---

## Plan de test

### Tests unitaires

- `EnvironmentSpec` portant un hôte → le corps envoyé contient
  `config.networking.allowed_hosts = ["api.githubcopilot.com"]`.
- Liste d'hôtes vide → la clé `allowed_hosts` est **absente** du corps (non-régression du corps
  d'avant SF-31-07).
- `AtelierAgentBootstrapService` dérive l'hôte de `app.git.mcp-server-url` et le transmet dans la
  spécification.
- URL MCP malformée ou sans hôte → aucun hôte, bootstrap mené à son terme.

### Tests d'intégration

Le corps est vérifié sur le serveur HTTP de test déjà utilisé par
`AnthropicManagedAgentProviderTest` — c'est le JSON réellement émis qui est assert, pas un double.

### Isolation workspace

Sans objet : l'environnement est une ressource de plateforme, provisionnée une fois, sans donnée
utilisateur. Aucun accès aux données n'est ajouté ni modifié.

---

## Dépendances

### Subfeatures bloquantes

Aucune. SF-31-05 est livrée ; c'est elle que ce correctif complète.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

**Décision — dériver l'hôte plutôt que le configurer à part.** Une seconde propriété
(`app.atelier.agent.allowed-hosts`) devrait rester cohérente avec `app.git.mcp-server-url` à la main :
c'est précisément ce type de duplication qui a produit ce défaut. L'hôte autorisé est donc déduit de
l'URL MCP déjà configurée — changer l'URL suffit, l'autorisation suit.

**Constat conservé — le correctif ne répare pas l'existant.** Un environnement déjà provisionné n'est
jamais revu. C'est pour cela que la production a été corrigée par un appel direct, tracé dans cette
mini-spec ; le code garantit seulement qu'aucune nouvelle installation ne rencontrera le problème.
