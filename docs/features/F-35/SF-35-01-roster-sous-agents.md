# Mini-spec — [F-35 / SF-01] Roster de sous-agents, plafonné et borné par le budget de session (backend)

---

## Identifiant

`F-35 / SF-01`

## Feature parente

`F-35` — Sous-agents

## Statut

`done` — livrée le 2026-08-26 (PR #168)

## Date de création

2026-08-26

## Branche Git

`feat/SF-35-01-roster-sous-agents`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Permettre à la session d'agent de **déléguer** des sous-tâches à des copies d'elle-même
(`multiagent: coordinator`), avec un **plafond** du nombre de sous-agents et un **budget de session
majoré** quand la délégation est active — le tout derrière un flag conservé pour couper sans
redéployer.

---

## Contexte

Un run est strictement séquentiel : sur « audite ce projet » ou « corrige tous les tests rouges »,
l'agent traite en série et remplit son contexte de lectures.

L'API expose déjà la capacité — `multiagent: {type: "coordinator", agents: [...]}` sur la
configuration d'agent. **Provider-First** : on la relaie, on n'écrit aucun ordonnanceur de tâches
côté Gateway (ce serait un moteur d'IA, interdit par `PROJECT.md` §3.2).

### Ce que la révision D1 (owner, 2026-08-26) change

Le cadrage d'origine voulait livrer **désactivé**, sur l'argument « chaque sous-agent consomme sa
propre session sandbox facturée ». Cet argument est **faux** : les sous-agents sont des **threads de
la même session** — un seul conteneur, aucune multiplication du coût de bac à sable, et l'usage
remonté au niveau session inclut déjà tous les threads.

Le second argument — « le surcompteur constate au lieu d'empêcher » — était vrai de notre
implémentation, mais **F-36 y répond** : `budget.max_list_cost` est un plafond **dur**, partagé entre
threads, appliqué en **verrou pré-requête**. **F-36 SF-36-01 et SF-36-02 sont livrées** (PR #164,
#165) : la condition impérative du cadrage est remplie.

Décision retenue : **livrer activé**. Une capacité livrée mais désactivée n'est pas testée — elle
reste du code mort en production dont on découvrirait le comportement le jour de son activation. Le
flag est **conservé** pour couper en une variable d'environnement.

Le risque résiduel n'est plus financier mais **qualitatif** : déléguer une tâche qui n'en valait pas
la peine coûte plus pour un résultat qui n'est pas meilleur. Cela se mesure à l'usage.

---

## Comportement attendu

### Cas nominal

1. `app.atelier.agent.subagents-enabled` vaut `true` (défaut). À chaque **ouverture** de session,
   celle-ci est créée avec un roster de `max-subagents` entrées `{type: "self"}`.
2. Le **budget de la session** (F-36 / SF-36-01) est calculé avec le plafond par run **majoré** —
   `cost.max-run-cost-delegated` (5 $) au lieu de `cost.max-run-cost` (2 $) — et reste borné, comme
   avant, par le quota restant converti en dollars et par le plancher `min-run-cost`. C'est le
   « pré-vol renforcé » (D4) dans sa forme livrée : un verrou **pré-requête**, partagé entre threads.
3. Le flag mis à `false` : la session est créée **exactement comme avant F-35** — aucun champ
   `multiagent`, budget calculé sur `max-run-cost`. Retour au comportement d'aujourd'hui en une
   variable d'environnement, sans redéploiement.
4. La délégation est **fixée à l'ouverture** et vaut pour toute la vie de la session, comme la
   politique d'outils (F-33), le prompt système (F-34) et le budget (F-36) : le fournisseur ne permet
   pas de la changer sur une session ouverte.
5. Les pré-vols existants (`assertWithinQuota`, `assertWithinSandboxLimit`) restent inchangés et
   s'appliquent avant : rien n'est retiré, la délégation s'ajoute.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Workspace inexistant ou appartenant à un autre utilisateur | `404 not_found` — `requireOwned` d'abord, aucun appel fournisseur |
| Quota de la période épuisé | `quota_exceeded` dans le flux SSE — inchangé, avant toute création de session |
| Plafond de bac à sable atteint | `sandbox_limit` dans le flux SSE — inchangé |
| Quota restant inférieur au plafond majoré | Budget = quota restant (le `min` de F-36 joue), jamais le plafond majoré |
| Quota restant quasi nul | Budget = plancher `min-run-cost` — inchangé par cette SF |
| Budget atteint pendant un run délégué | Comportement F-36 inchangé : threads en pause, tour marqué `budgetReached` |
| `max-subagents` configuré à `0` ou négatif | Ramené au défaut `3` par la normalisation des propriétés |
| Fournisseur refusant le champ `multiagent` | `provider_error` dans le flux (chemin existant) ; le flag permet de couper immédiatement |

---

## Critères d'acceptation

- [ ] `app.atelier.agent.subagents-enabled` existe, **défaut `true`** (révision D1), pilotable par
      `APP_ATELIER_AGENT_SUBAGENTS_ENABLED`
- [ ] `app.atelier.agent.max-subagents`, **défaut `3`**, normalisé (`<= 0` ⇒ `3`)
- [ ] Délégation active ⇒ le corps porte
      `agent.multiagent = {type: "coordinator", agents: [{type:"self"} × max-subagents]}`
- [ ] Délégation active ⇒ le budget de session est calculé sur `cost.max-run-cost-delegated`
      (propriété laissée **dormante** par SF-36-01, réveillée ici)
- [ ] Délégation active ⇒ le budget reste **borné par le quota restant** et par le plancher
- [ ] Flag à `false` ⇒ corps de création de session **strictement inchangé** (aucun `multiagent`) et
      budget calculé sur `cost.max-run-cost` — retour intégral au comportement d'avant F-35
- [ ] La délégation coexiste avec le prompt système (F-34), la politique d'outils (F-33), le MCP
      (F-31) et le budget (F-36) dans la **même** création de session
- [ ] Le domaine n'exprime la délégation que par `DelegationPolicy` : aucune forme JSON Anthropic hors
      du provider (Provider Independence)
- [ ] `requireOwned(userId, workspaceId)` reste la **première** instruction du run
- [ ] Aucune clé d'API, aucun jeton, aucun contenu utilisateur journalisé sur ce chemin

---

## Périmètre

### Hors scope

- Provenance des sous-tâches relayée au frontend → **SF-35-02**
- Affichage des sous-tâches dans la vue terminal → **SF-35-03**
- Budget de session et coût réel → **F-36** (livrées : SF-36-01, SF-36-02)
- Roster nommé (agents dédiés), `advisor` sur un autre modèle, choix du modèle par l'utilisateur
- Option par projet (le flag est **global** : aucune migration)
- Mesure du **taux de délégation** (recommandation du cadrage révisé) : observation, pas code

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| `subagents-enabled` | Booléen de **configuration**, jamais exposé à l'utilisateur ; défaut `true` |
| `max-subagents` | Entier `>= 1` après normalisation ; défaut `3` (D3 du cadrage) |
| Plafond de dépense | `cost.max-run-cost-delegated` (défaut 5 $) quand la délégation est active, sinon `cost.max-run-cost` (défaut 2 $) — dans les deux cas borné par le quota restant |
| Portée | **Globale** (configuration serveur), jamais par workspace : aucune colonne, aucune migration |
| Moment d'application | À l'**ouverture** de session ; une session ouverte garde sa politique |
| Roster | `{type: "self"}` uniquement (D2 du cadrage), jamais un identifiant d'agent |

---

## Technique

### Contrat API

**Aucun changement de contrat HTTP ni SSE.** La délégation est un réglage serveur invisible du client.

### Tables impactées / Migration

**Aucune.** Le flag est global et vit en configuration. **Pas de migration Liquibase**, donc aucun
numéro ni UUID à pré-assigner.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/agent/DelegationPolicy.java` | **Nouveau** — politique de délégation **du domaine** |
| `atelier/agent/AtelierAgentProperties.java` | `subagentsEnabled` + `maxSubagents` + normalisation |
| `atelier/agent/ManagedAgentProvider.java` | Surcharge `createSession(..., DelegationPolicy)` |
| `atelier/agent/AnthropicManagedAgentProvider.java` | Traduction en `agent_with_overrides.multiagent` |
| `atelier/agent/AtelierSessionService.java` | Politique résolue à l'ouverture ; budget majoré si déléguée |
| `resources/application.yml` | Les deux réglages, commentés |

---

## Plan de test

### Tests unitaires

- [ ] `DelegationPolicy.of(false, 3)` ⇒ `DISABLED` ; `of(true, 0)` ⇒ `DISABLED` (roster vide ≠ délégation)
- [ ] Flag ON (défaut) ⇒ `createSession` reçoit une politique active à `max-subagents`
- [ ] Flag OFF ⇒ `createSession` reçoit `DelegationPolicy.DISABLED` (retour au comportement d'avant)
- [ ] Flag ON ⇒ le budget transmis vaut `max-run-cost-delegated` (et non `max-run-cost`)
- [ ] Flag OFF ⇒ le budget transmis vaut `max-run-cost` (non-régression F-36)
- [ ] Flag ON + quota restant faible ⇒ le budget reste celui du quota restant, pas le plafond majoré
- [ ] Workspace Git : la politique **et** le budget majoré sont transmis sur ce chemin aussi
- [ ] Provider : `DISABLED` ⇒ corps **sans** `multiagent` (identifiant d'agent nu si rien d'autre)
- [ ] Provider : politique active ⇒ `agent.multiagent.agents` = exactement `max-subagents` × `self`
- [ ] Provider : délégation + prompt système + politique d'outils ⇒ tout dans le même
      `agent_with_overrides`

### Tests d'intégration

- [ ] Run d'exécution : `done` émis, aucun champ de contrat nouveau
- [ ] Pré-vols quota/sandbox ⇒ `quota_exceeded` / `sandbox_limit` inchangés

### Isolation utilisateur

- [x] **Applicable** — `requireOwned(userId, workspaceId)` reste la première instruction de `run`.
  Le budget est dérivé du quota **du `userId` du JWT**, jamais d'un identifiant reçu du client. Le
  provider ne reçoit aucun identifiant d'utilisateur.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement du Principal ni du mode d'authentification. Aucun endpoint nouveau ou modifié. |
| Contexte tenant | **Non** | Aucun nouveau chemin de résolution du tenant : la politique de délégation ne dépend d'aucun utilisateur, et le budget passe par `sessionBudget(userId)` **déjà en place** (F-36), qui lit le quota du `userId` résolu par `currentUser.requireId()`. |
| Plans / limites | **Oui** | Le **plafond de dépense** d'une session déléguée change (2 $ → 5 $, toujours borné par le quota restant). Appels aux limites sur ce chemin, tous vérifiés : `assertWithinQuota` (inchangé, avant tout), `assertWithinSandboxLimit` (inchangé, avant tout), `sessionBudget` (**modifié** : choisit le plafond selon la délégation ; le `min` avec le quota restant et le plancher sont conservés tels quels), `recordUsage` / `recordSandboxSeconds` (inchangés — l'usage de session couvre déjà tous les threads). Le gating d'accès Gold/ADMIN (`AtelierAccessService`) est inchangé. |
| Navigation / routing | **Non** | Aucune route, aucun guard, aucun écran. |

---

## Notes

**Pourquoi le budget majoré remplace le pré-vol en tokens.** Le cadrage (D4) demandait un « pré-vol de
quota renforcé ». Un compteur de tokens supplémentaire aurait doublonné avec F-36 sans rien garantir :
il *constate*, là où `budget.max_list_cost` *empêche*, avant chaque appel au modèle, et pour tous les
threads à la fois. D4 est donc satisfait par la dérivation du budget — qui reste bornée par le quota
restant — plutôt que par un second gate à maintenir. `max-run-cost-delegated` avait précisément été
posée **dormante** par SF-36-01 en attendant cette SF.

**Pourquoi un flag global et non une option par projet.** Le sujet est le comportement de la
plateforme, pas une préférence de projet. Un réglage serveur se referme en une variable
d'environnement ; une colonne par workspace aurait figé un choix dans les données.
