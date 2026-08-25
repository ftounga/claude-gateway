# Mini-spec — [F-35 / SF-01] Roster de sous-agents derrière flag, plafonné et pré-volé (backend)

---

## Identifiant

`F-35 / SF-01`

## Feature parente

`F-35` — Sous-agents (sessions parallèles)

## Statut

`ready`

## Date de création

2026-08-26

## Branche Git

`feat/SF-35-01-roster-sous-agents`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Permettre à la session d'agent de **déléguer** des sous-tâches à des copies d'elle-même
(`multiagent: coordinator`), **derrière un flag désactivé par défaut**, avec un **plafond** du nombre
de sous-agents et un **pré-vol de quota renforcé** qui retire la délégation quand la marge restante
ne couvrirait pas le pire cas.

---

## Contexte

Un run est strictement séquentiel : sur « audite ce projet » ou « corrige tous les tests rouges »,
l'agent traite en série et remplit son contexte de lectures.

L'API expose déjà la capacité — `multiagent: {type: "coordinator", agents: [...]}` sur la
configuration d'agent. **Provider-First** : on la relaie, on n'écrit aucun ordonnanceur de tâches
côté Gateway (ce serait un moteur d'IA, interdit par `PROJECT.md` §3.2).

**Le point qui commande tout, c'est le coût** : chaque sous-agent consomme **sa propre session
sandbox facturée**, et le surcompteur sandbox (SF-28-12) est alimenté *après* coup — il constate le
dépassement, il ne l'empêche pas. C'est la seule capacité de cette vague dont le coût n'est pas
réversible : des sessions facturées ne se récupèrent pas.

D'où la posture de cette SF, qui est celle de la Phase 2 à ses débuts (SF-28-08) : **le code est
livré, le robinet reste fermé**. Le plafond de dépense **dur** par session (`budget.max_list_cost`)
est le sujet de **F-36**, non livrée : tant qu'elle ne l'est pas, l'activation en production reste
une décision humaine explicite — elle n'est pas prise ici.

---

## Comportement attendu

### Cas nominal

1. `app.atelier.agent.subagents-enabled` vaut `false` (défaut) : la session est créée **exactement
   comme avant F-35** — aucun champ `multiagent` dans le corps. Zéro régression, zéro coût.
2. Le flag activé, à chaque **ouverture** de session :
   - le service demande au quota s'il reste au moins `subagent-headroom-tokens × (maxSubagents + 1)`
     tokens sur la période ;
   - **oui** ⇒ la session est ouverte avec un roster de `maxSubagents` entrées `{type: "self"}` ;
   - **non** ⇒ la session est ouverte **sans délégation**, et le run se déroule en séquentiel.
3. La politique de délégation est **fixée à l'ouverture** et vaut pour toute la vie de la session,
   comme la politique d'outils (F-33) et le prompt système (F-34) : le fournisseur ne permet pas de
   la changer sur une session ouverte.
4. Le pré-vol de quota existant (`assertWithinQuota` + `assertWithinSandboxLimit`) reste inchangé et
   s'applique **avant** : le renfort s'ajoute, il ne remplace rien.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Workspace inexistant ou appartenant à un autre utilisateur | `404 not_found` — `requireOwned` d'abord, aucun appel fournisseur |
| Quota de la période épuisé | `quota_exceeded` dans le flux SSE — inchangé, avant toute création de session |
| Plafond de bac à sable atteint | `sandbox_limit` dans le flux SSE — inchangé |
| Marge de quota insuffisante pour le pire cas de délégation | **Aucune erreur** : la session s'ouvre sans délégation, le run réussit en séquentiel |
| `max-subagents` configuré à `0` ou négatif | Ramené au défaut `3` par la normalisation des propriétés |
| Flag activé mais le fournisseur refuse le champ `multiagent` | `provider_error` dans le flux (chemin d'erreur existant) — visible immédiatement, ce qui est l'intérêt d'un flag |

---

## Critères d'acceptation

- [ ] `app.atelier.agent.subagents-enabled` existe, **défaut `false`**, pilotable par variable
      d'environnement `APP_ATELIER_AGENT_SUBAGENTS_ENABLED`
- [ ] `app.atelier.agent.max-subagents`, **défaut `3`**, normalisé (`<= 0` ⇒ `3`)
- [ ] `app.atelier.agent.subagent-headroom-tokens`, **défaut `50000`**, normalisé (`<= 0` ⇒ `50000`)
- [ ] Flag **off** ⇒ le corps de création de session est **strictement inchangé** (aucun champ
      `multiagent`, et pas de bascule en `agent_with_overrides` si rien d'autre ne l'exigeait)
- [ ] Flag **on** + marge suffisante ⇒ le corps porte
      `agent.multiagent = {type: "coordinator", agents: [{type:"self"} × maxSubagents]}`
- [ ] Flag **on** + marge insuffisante ⇒ corps **sans** `multiagent` (dégradation, jamais un refus)
- [ ] La délégation coexiste avec le prompt système (F-34), la politique d'outils (F-33) et le MCP
      (F-31) dans le **même** `agent_with_overrides`
- [ ] Le domaine n'exprime la délégation que par `DelegationPolicy` : aucune forme JSON Anthropic hors
      du provider (Provider Independence)
- [ ] `requireOwned(userId, workspaceId)` reste la **première** instruction du run
- [ ] Aucune clé d'API, aucun jeton, aucun contenu utilisateur journalisé sur ce chemin

---

## Périmètre

### Hors scope

- Agrégation du coût des sous-agents dans le tour → **SF-35-02**
- Affichage des sous-tâches dans la vue terminal → **SF-35-03**
- Plafond de dépense **dur** par session (`budget.max_list_cost`) et facturation au coût réel → **F-36**
- Roster nommé (agents dédiés), `advisor` sur un autre modèle, choix du modèle par l'utilisateur
  (hors scope du cadrage F-35)
- Activation en production : décision humaine, hors de cette livraison
- Option par projet (le flag est **global**, donc aucune migration : voir Contraintes)

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| `subagents-enabled` | Booléen de **configuration**, jamais exposé à l'utilisateur ; défaut `false` |
| `max-subagents` | Entier `>= 1` après normalisation ; défaut `3` (D3 du cadrage) |
| `subagent-headroom-tokens` | Entier `>= 1` après normalisation ; défaut `50 000` |
| Marge exigée | `subagent-headroom-tokens × (max-subagents + 1)` — le coordinateur **plus** ses sous-agents |
| Portée | **Globale** (configuration serveur), jamais par workspace : aucune colonne, aucune migration |
| Moment d'application | À l'**ouverture** de session ; une session ouverte garde sa politique |
| Roster | `{type: "self"}` uniquement (D2 du cadrage), jamais un identifiant d'agent |

---

## Technique

### Contrat API

**Aucun changement de contrat HTTP.** Ni endpoint, ni champ de réponse : la délégation est un réglage
serveur invisible du client. Le contrat SSE de `POST /api/workspaces/{id}/agent/stream` est inchangé.

### Tables impactées / Migration

**Aucune.** Le flag est global et vit en configuration — pas de colonne, donc **pas de migration
Liquibase**, donc aucun numéro ni UUID à pré-assigner.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/agent/DelegationPolicy.java` | **Nouveau** — politique de délégation **du domaine** |
| `atelier/agent/AtelierAgentProperties.java` | 3 réglages + normalisation |
| `atelier/agent/ManagedAgentProvider.java` | Surcharge `createSession(..., DelegationPolicy)` |
| `atelier/agent/AnthropicManagedAgentProvider.java` | Traduction en `agent_with_overrides.multiagent` |
| `atelier/agent/AtelierSessionService.java` | Résolution de la politique à l'ouverture (archive + Git) |
| `quota/QuotaService.java` | `hasRemainingTokens(userId, tokens)` (lecture seule) |
| `resources/application.yml` | Les 3 réglages, commentés |

---

## Plan de test

### Tests unitaires

- [ ] `DelegationPolicy.of(false, 3)` ⇒ `DISABLED` ; `of(true, 0)` ⇒ `DISABLED` (roster vide = pas de délégation)
- [ ] Flag off ⇒ `createSession` reçoit `DelegationPolicy.DISABLED` (non-régression)
- [ ] Flag on + marge suffisante ⇒ `createSession` reçoit une politique active à `maxSubagents`
- [ ] Flag on + marge insuffisante ⇒ `createSession` reçoit `DISABLED`, le run **aboutit** malgré tout
- [ ] Workspace Git : la politique est transmise sur ce chemin aussi
- [ ] Provider : `DISABLED` ⇒ corps **sans** `multiagent` (et identifiant d'agent nu si rien d'autre)
- [ ] Provider : politique active ⇒ `agent.multiagent.agents` contient exactement `maxSubagents`
      entrées `{type: "self"}`
- [ ] Provider : délégation + prompt système + politique d'outils ⇒ tout dans le même
      `agent_with_overrides`
- [ ] `QuotaService.hasRemainingTokens` : vrai quand la marge est là, faux sinon, vrai pour `<= 0`

### Tests d'intégration

- [ ] Run d'exécution avec flag off ⇒ inchangé (`done` émis, aucun champ nouveau)
- [ ] Pré-vols quota/sandbox ⇒ `quota_exceeded` / `sandbox_limit` inchangés

### Isolation utilisateur

- [x] **Applicable** — `requireOwned(userId, workspaceId)` reste la première instruction de `run`.
  Le pré-vol renforcé lit le compteur d'usage **du `userId` du JWT**, jamais d'un identifiant reçu du
  client. Le provider ne reçoit aucun identifiant d'utilisateur.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement du Principal ni du mode d'authentification. Aucun endpoint nouveau ou modifié. |
| Contexte tenant | **Oui** | Un nouveau chemin lit le quota d'un utilisateur. Composants vérifiés : `AtelierAgentController` (`currentUser.requireId()` seul), `AtelierSessionService.run` (`requireOwned` en première instruction, `userId` propagé jusqu'à `resolveDelegation`), `QuotaService.hasRemainingTokens` (filtre `user_id` via `findByUserIdAndPeriodStart`, comme les autres méthodes de quota), `AnthropicManagedAgentProvider` (ne reçoit qu'une politique, aucun identifiant utilisateur). Aucun autre composant ne résout de tenant sur ce chemin. |
| Plans / limites | **Oui** | Nouveau **gate de coût**. Appels aux limites sur ce chemin, tous vérifiés : `assertWithinQuota` (inchangé, avant tout), `assertWithinSandboxLimit` (inchangé, avant tout), `hasRemainingTokens` (**nouveau**, purement additif : il ne peut que **retirer** la délégation, jamais refuser un run qui passait). Le gating d'accès Gold/ADMIN (`AtelierAccessService`) est inchangé. |
| Navigation / routing | **Non** | Aucune route, aucun guard, aucun écran. |

---

## Notes

**Pourquoi dégrader plutôt que refuser.** Le cadrage (D4) dit « refuser avant d'engager coûte zéro ».
Refuser le **run** parce que la délégation ne tiendrait pas dans la marge enfermerait l'utilisateur :
il ne demandait pas de délégation, c'est un réglage serveur. Le refus porte donc sur **la délégation**,
pas sur le run — on refuse bien avant d'engager, mais on n'ampute pas une capacité déjà payée.

**Pourquoi un flag global et non une option par projet.** Le sujet est le **coût de la plateforme**,
pas une préférence de projet. Un réglage serveur se change sans migration et se referme
instantanément ; une colonne par workspace aurait figé un choix dans les données pour une capacité
qu'on n'a pas encore observée en production.
