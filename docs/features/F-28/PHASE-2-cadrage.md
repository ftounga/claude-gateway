# Cadrage — Claude Code Lite Phase 2 (Managed Agents / exécution)

> Document de cadrage préalable. Décision architecturale formalisée en **ADR-013** (`docs/ADR.md`).
> Statut : **Proposé** — un seul verrou bloquant restant (grille de coût sandbox, §7).

## 1. Objectif & valeur

La Phase 1 (F-28, livrée) donne un Atelier où Claude **lit et édite** les fichiers d'un projet via une boucle tool-use **sans exécution** (opérations fichiers uniquement, orchestrées par notre backend). La **Phase 2** ajoute l'**exécution réelle** : Claude peut lancer `bash`, installer des dépendances, **exécuter du code**, faire tourner les tests, builder — dans un **sandbox hébergé par Anthropic**, puis nous récupérons les fichiers modifiés.

C'est le saut « éditeur intelligent » → « véritable agent de développement », qui justifie l'offre **Gold premium +++**.

Principe directeur inchangé : **Gateway-First / Provider-First**. On **relaie** la capacité **Managed Agents** d'Anthropic (Anthropic exécute la boucle d'agent ET héberge le conteneur d'exécution). On ne construit **ni moteur d'agent, ni sandbox maison**.

## 2. Phase 1 vs Phase 2

| | Phase 1 (livrée) | Phase 2 (ce cadrage) |
|---|---|---|
| Boucle d'agent | notre backend (`AtelierChatService`) | **Anthropic** (Managed Agents) |
| Outils | `read/write/list/search` fichiers (backend) | `bash`, exécution de code, fichiers, **+ MCP** (dans le sandbox Anthropic) |
| Exécution | ❌ aucune | ✅ conteneur éphémère hébergé Anthropic |
| Stockage projet | S3 (`atelier/`) | S3 → **monté** dans la session → resync S3 |
| Streaming | SSE étapes fichier (SF-28-05) | SSE **events** riches (bash, code, diffs) |
| Coût | tokens (quota/BYOK) | tokens **+ minutes de sandbox** |

## 3. Expérience utilisateur

Flux unique fidèle à Claude Code (comme aujourd'hui), mais Claude affiche en direct : commandes `bash`, sorties, résultats de tests, fichiers créés/modifiés. À la fin d'une tâche, l'arborescence du workspace reflète les changements réellement exécutés (pas seulement proposés). Un **bandeau de coût** (minutes sandbox consommées / plafond) reste visible — cohérent avec le modèle facturé.

## 4. Architecture cible

### 4.1 Abstraction `AgentProvider` (parallèle d'`AIProvider`)
Nouvelle interface backend `AgentProvider` (indépendance fournisseur), impl. `AnthropicManagedAgentProvider` via le **SDK Java Anthropic** (`client.beta().agents()/environments()/sessions()/files()`, beta header `managed-agents-2026-04-01`). Aucune dépendance directe d'Anthropic dans le code métier de l'Atelier.

### 4.2 Objets Managed Agents
- **Environment** (template réutilisable, créé **une fois** au bootstrap) : `config.type = "cloud"` (sandbox Anthropic), `networking = limited` avec `allow_package_managers: true` (npm/pip) et egress restreint (deny-by-default) → surface d'attaque minimale.
- **Agent** (config **persistée + versionnée**, créée **une fois** par version de config) : `model` (Opus 4.8), `system` = conventions projet (`CLAUDE.md` + skills du workspace), `tools` = toolset agent + exécution de code, `skills`. **Jamais recréé dans le hot-path** — l'`agent_id`/version est stocké en config/DB.
- **Session** (créée **par tâche/message** de l'utilisateur) : référence l'agent + l'environment, `resources` = fichiers du workspace **montés** (voir 4.3), `vault_ids` = credential BYOK le cas échéant. Éphémère.

### 4.3 Pont fichiers S3 ⇄ Session (bidirectionnel)
- **Entrée** : chaque fichier du workspace S3 → `POST /v1/files` (`purpose: agent`) → `resources: [{type:"file", file_id, mount_path:"/workspace/…"}]`. (Montage read-only ; l'agent écrit les versions modifiées ailleurs.) Alternative pour un vrai dépôt : `type:"github_repository"` (clone + `git` via proxy Anthropic, token jamais dans le conteneur).
- **Sortie** : l'agent écrit dans `/mnt/session/outputs/` → à la fin, `files.list({scope_id: session.id})` + download → **resync dans S3** (mêmes garde-fous zip-bomb/plafonds que Phase 1). L'arborescence de l'UI est rafraîchie.

### 4.4 Streaming
`GET /v1/sessions/{id}/events/stream` (SSE) → relayé au frontend via notre patron SSE existant (SF-28-05), enrichi (types d'events : message, tool_use bash, exécution, écriture fichier). **Reconnexion avec consolidation** (le flux Anthropic n'a pas de replay : à chaque (re)connexion, lister `GET /events` + dédup par id).

### 4.5 Sécurité
- **Sandbox = celui d'Anthropic** (jamais maison). Sessions **éphémères**, `networking limited`.
- **Isolation `user_id`** stricte : une session est créée pour un workspace **possédé** par l'utilisateur (`requireOwned`), fichiers montés uniquement depuis SON préfixe S3.
- **Secrets via Vaults** (jamais dans le conteneur) ; tokens Git via **git-proxy** Anthropic (injectés après sortie du sandbox).
- **BYOK** : clé Anthropic de l'utilisateur → ses tokens **et** son sandbox facturés sur son compte (via credential vault / clé de la session). **Hosted** : clé plateforme, compté + marqué + **plafonné**.

## 5. Modèle économique (rappel ADR-012, à figer au branchement Stripe)

3 briques : (1) **Accès Gold** (abonnement fixe, déjà livré SF-28-06) ; (2) **Conso Hosted** = tokens ~**2× coût Anthropic** **+ frais par minute de sandbox** ; (3) **BYOK** = accès Gold seul (conso sur le compte de l'utilisateur).

**Garde-fous obligatoires** (nouveaux vs Phase 1) : **plafond de dépense par utilisateur et par tâche**, ceilings **minutes de sandbox** + tokens par session, timeout dur de session, **surcompteur sandbox** (nouvelle dimension d'usage, à côté du quota tokens F-10).

## 6. Découpage proposé en subfeatures (post-décision)

| SF | Objet | Note |
|---|---|---|
| SF-28-08 | Abstraction `AgentProvider` + bootstrap Environment/Agent (config, versionné) + dépendance SDK Anthropic beta | fondation, pas d'UI |
| SF-28-09 | Cycle de session : montage fichiers S3→session, exécution, resync outputs→S3 (garde-fous) | cœur |
| SF-28-10 | Streaming des events de session → écran Atelier (mode « exécution ») + reconnexion consolidée | UI |
| SF-28-11 | Surcompteur **sandbox** + **plafonds** dépense/minutes + bandeau coût + intégration quota/billing | ZONE ARGENT |
| SF-28-12 | Bascule par workspace Phase 1 (édition) ↔ Phase 2 (exécution), derrière flag + offre Gold | rollout progressif |

Chaque SF suit le cycle de gouvernance habituel (mini-spec → readiness → dev → review → PR → merge).

## 7. Verrou bloquant restant (à trancher AVANT tout dev)

- **Grille de coût sandbox Anthropic** : le tarif par **minute/session de conteneur** n'est pas dans la doc API — à confirmer sur la pricing page / console Anthropic. Il conditionne : le markup Hosted, les plafonds, et le prix effectif de la brique (2). **Sans ce chiffre, on ne peut pas garantir la rentabilité → on ne démarre pas SF-28-11.**
- Décision owner attendue : (a) valider le markup (≈2×) et les plafonds par défaut une fois le coût connu ; (b) confirmer que le **compte Anthropic mutualisé (legalcase)** a accès au beta Managed Agents (dispo générale = ✅ β first-party, mais accès **par compte** à vérifier).

## 8. Verrous déjà levés

- **Disponibilité** : Managed Agents = **β sur l'API Anthropic first-party** (notre clé) et Claude Platform on AWS. (❌ Bedrock/Vertex/Foundry — non pertinent ici.)
- **Faisabilité SDK** : SDK Java officiel (`client.beta().agents()/sessions()/environments()/files()`).
- **Pont fichiers** : mécanisme natif (Files API + resources + `/mnt/session/outputs/`).
- **Alignement archi** : relais pur (Gateway-First/Provider-First), réutilise BYOK/quota/S3/SSE existants.

## 9. Risques & mitigations

| Risque | Mitigation |
|---|---|
| Variance de coût (exécution) | plafonds durs dépense/minutes + markup + BYOK par défaut |
| Beta instable / breaking changes | abstraction `AgentProvider` isole le code métier ; header beta épinglé |
| Fuite entre tenants | isolation `user_id` + fichiers montés depuis le seul préfixe S3 du user ; sessions éphémères |
| Exfiltration via réseau | `networking limited` (deny-by-default) ; secrets en vault ; git-proxy |
| Latence de montage (999 fichiers max/session) | plafonner la taille/nb de fichiers montés ; gros projets via `github_repository` |
