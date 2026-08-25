# Mini-spec — [F-35 / SF-02] Provenance des sous-tâches et coût agrégé (backend)

---

## Identifiant

`F-35 / SF-02`

## Feature parente

`F-35` — Sous-agents

## Statut

`done` — livrée le 2026-08-26 (PR #170)

## Date de création

2026-08-26

## Branche Git

`feat/SF-35-02-provenance-sous-taches`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire porter à chaque commande relayée **le fil d'exécution dont elle vient**, pour que l'écran puisse
distinguer une sous-tâche du travail principal — et **verrouiller par un test** le fait que le coût du
tour agrège déjà tous les fils (décision D5 du cadrage).

---

## Contexte

Depuis SF-35-01, une session peut ouvrir un roster de sous-agents. Deux questions se posent alors.

**La provenance.** Les events d'outil d'un run délégué viennent de plusieurs fils. Sans marqueur, la
vue terminal entrelace les commandes de trois sous-tâches dans un flux unique où plus rien ne se lit —
le flux devient illisible exactement au moment où la délégation devrait le rendre plus rapide. Le
backend est le seul endroit où l'information existe : elle est relayée ici, l'affichage est
**SF-35-03**.

**Le coût (D5).** Le cadrage exige que le coût du tour **agrège** les sous-agents, faute de quoi
l'utilisateur verrait un coût faux — le piège que SF-30-05 avait évité en n'affichant rien plutôt
qu'un « 0 token » mensonger. La révision D1 du cadrage établit que les sous-agents sont des **threads
de la même session** : `getSessionUsage` lit `usage`, `stats.active_seconds` et `list_cost` **au niveau
session**, qui couvrent donc déjà tous les fils. **D5 est satisfait par construction** — il n'y a rien à
ajouter, mais il y a quelque chose à **empêcher de régresser** : passer un jour ces relevés au niveau
d'un fil sous-compterait silencieusement. D'où un test qui fige ce point.

La forme exacte du marqueur de fil n'est pas documentée. L'extraction est donc **défensive**, comme
celle des sorties d'outil de SF-30-01 : une forme inattendue produit l'ancien comportement, jamais une
exception — un run ne doit jamais échouer à cause de l'affichage.

---

## Comportement attendu

### Cas nominal

1. Pendant le polling, chaque event d'outil (`agent.tool_use`, `agent.custom_tool_use`,
   `agent.tool_result`, `agent.mcp_tool_result`) est lu pour en extraire un identifiant de fil
   (`thread_id`, repli `thread`).
2. Cet identifiant est relayé à l'écouteur (`onAction` / `onActionResult`), puis :
   - porté par l'événement SSE `action` / `action_result` sous la clé `threadId` (**champ additif**) ;
   - conservé dans la transcription persistée (`terminal_json`), pour qu'un rechargement de page
     relise les mêmes sous-tâches.
3. Sans délégation, aucun event ne porte de fil : `threadId` vaut `null` et tout se comporte
   exactement comme avant — aucun champ nouveau visible dans le flux, aucun bloc marqué.
4. Le décompte de la consommation est **inchangé** : `getSessionUsage` reste lu au niveau **session**,
   ce qui couvre tous les fils, et le service décompte toujours le **delta** depuis le relevé précédent.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Event d'outil sans `thread_id` | `threadId = null` — bloc rattaché au fil principal, comportement d'avant F-35 |
| `thread_id` présent mais vide ou non textuel | Traité comme absent (`null`) |
| Écouteur n'implémentant que les anciennes signatures | Toujours notifié (les surcharges sont `default` et délèguent) |
| Échec du relevé d'usage | Inchangé : best-effort, tour livré, coût affiché « inconnu » |
| Transcription trop volumineuse | Inchangé : bornée par `max-transcript-chars`, blocs omis comptés |
| Tour relu écrit avant cette SF | Blocs sans `threadId` — lisible, sans marquage |

---

## Critères d'acceptation

- [ ] `agent.tool_use` / `agent.custom_tool_use` portant un `thread_id` ⇒ relayé à `onAction`
- [ ] `agent.tool_result` / `agent.mcp_tool_result` portant un `thread_id` ⇒ relayé à `onActionResult`
- [ ] L'événement SSE `action` porte `threadId` ; `action_result` aussi (**champs additifs**, `null`
      quand inconnu)
- [ ] La transcription persistée porte `threadId` par bloc
- [ ] L'appariement commande ↔ sortie reste inchangé (priorité au `toolUseId`, repli sur la dernière
      commande sans sortie, bloc orphelin sinon) ; le fil est conservé dans les trois cas
- [ ] Les surcharges d'écouteur ajoutées sont `default` et délèguent aux anciennes : aucune
      implémentation existante n'est cassée
- [ ] **D5** : un test fige le fait que le coût/usage du tour est lu au niveau **session** (donc
      couvre tous les fils), et non au niveau d'un fil
- [ ] Le domaine ne connaît aucune forme JSON Anthropic : l'extraction reste confinée au provider
- [ ] Aucune clé d'API ni donnée utilisateur journalisée sur ce chemin

---

## Périmètre

### Hors scope

- Affichage des sous-tâches à l'écran → **SF-35-03**
- Coût **par** sous-tâche : le tour affiche un coût agrégé (D5), et le quota reste un total
- Facturation au coût réel, plafond de dépense → **F-36** (livrées)
- Interprétation de l'identifiant de fil (ordre, hiérarchie) : c'est une chaîne opaque

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| `threadId` | Chaîne opaque du fournisseur ou `null` ; jamais interprétée, jamais journalisée |
| Champs reconnus | `thread_id`, repli `thread` — tout autre champ est ignoré |
| Compatibilité | Tous les champs ajoutés sont **additifs** : un client antérieur les ignore |
| Niveau du relevé d'usage | **Session** (jamais un fil) : c'est ce qui garantit D5 |

---

## Technique

### Contrat API (figé — importé tel quel par SF-35-03)

Évolution **additive** du flux SSE de `POST /api/workspaces/{id}/agent/stream` :

| Événement | Champ ajouté | Type | Sens |
|-----------|--------------|------|------|
| `action` | `threadId` | `string \| null` | Fil d'exécution dont vient la commande |
| `action_result` | `threadId` | `string \| null` | Fil d'exécution dont vient la sortie |

Évolution **additive** du document `terminal_json` renvoyé par `GET /api/workspaces/{id}/chat` :
chaque entrée de `blocks` porte `threadId` (`string | null`).

Aucun nouvel endpoint, aucun code d'erreur nouveau.

### Tables impactées / Migration

**Aucune.** `threadId` vit dans le document d'affichage `terminal_json` de la table
`atelier_messages` existante — même choix qu'en F-32 pour la marque d'interruption. **Pas de migration
Liquibase.**

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/agent/AnthropicManagedAgentProvider.java` | Extraction défensive du `thread_id` |
| `atelier/agent/ManagedEventListener.java` | Surcharges `default` portant le fil |
| `atelier/agent/AtelierAgentListener.java` | Miroir applicatif des mêmes surcharges |
| `atelier/agent/AtelierSessionService.java` | Pont d'events : propage le fil |
| `atelier/agent/TerminalTranscript.java` | `Block` porte le fil |
| `atelier/AtelierAgentController.java` | `threadId` dans les événements SSE |

---

## Plan de test

### Tests unitaires

- [ ] Event d'outil avec `thread_id` ⇒ relayé à `onAction` ; sortie idem à `onActionResult`
- [ ] Event d'outil sans `thread_id` ⇒ `null` relayé, comportement inchangé
- [ ] Repli sur `thread` quand `thread_id` est absent
- [ ] `TerminalTranscript` : commande et sortie s'apparient ; le fil est conservé
- [ ] `TerminalTranscript` : bloc orphelin conserve le fil de la sortie
- [ ] `TerminalTranscript` : une commande sans fil qui reçoit une sortie avec fil adopte ce fil
- [ ] Écouteur n'implémentant que les anciennes signatures ⇒ toujours notifié
- [ ] **D5** : le relevé d'usage est demandé pour l'**identifiant de session** (couvre tous les fils)

### Tests d'intégration

- [ ] Flux SSE d'un run ⇒ `action` et `action_result` portent `threadId`
- [ ] Tour persisté ⇒ `terminal_json` contient `threadId`

### Isolation utilisateur

- [x] **Applicable** — chemin inchangé : `requireOwned(userId, workspaceId)` reste la première
  instruction du run, l'identifiant de session est lu sur le workspace **possédé**, et la
  transcription est écrite avec le `user_id` du JWT. Aucun identifiant de fil ne vient du client.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement du Principal ni du mode d'authentification. |
| Contexte tenant | **Non** | Aucun nouveau chemin de résolution du tenant : le run, la persistance du tour et le relevé d'usage passent tous par le `userId` déjà résolu et le workspace déjà possédé. |
| Plans / limites | **Non** | Aucun montant décompté ne change : le relevé d'usage reste lu au niveau session, exactement comme avant. Appels vérifiés : `assertWithinQuota`, `assertWithinSandboxLimit`, `recordUsage`, `recordSandboxSeconds` — tous inchangés. |
| Navigation / routing | **Non** | Aucune route, aucun guard, aucun écran. |

---

## Notes

**Pourquoi un test pour une propriété déjà vraie.** D5 tient aujourd'hui parce que le relevé est pris
au niveau session. Rien dans le code ne le *dit* : c'est un fait implicite, exactement le genre qu'une
refactorisation bien intentionnée casse en silence — et un sous-comptage ne se voit pas, il se
constate en fin de mois. Le test transforme l'implicite en contrat.
