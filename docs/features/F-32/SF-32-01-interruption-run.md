# Mini-spec — [F-32 / SF-01] Interrompre un run en cours (backend)

---

## Identifiant

`F-32 / SF-01`

## Feature parente

`F-32` — Interrompre un run en cours

## Statut

`ready`

## Date de création

2026-08-25

## Branche Git

`feat/SF-32-01-interruption-run-backend`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Relayer une demande d'**interruption** de l'utilisateur à la session Managed Agents en cours, pour que
le run s'arrête à une frontière sûre au lieu de tourner jusqu'au timeout de 10 minutes.

---

## Contexte

Aujourd'hui, une commande partie de travers (`npm install` sur un dépôt cassé, boucle, build
interminable) tourne jusqu'au plafond `sessionTimeout` (10 min, `AtelierAgentProperties`). L'utilisateur
regarde défiler la sortie sans pouvoir agir, et **le temps de bac à sable est facturé** (décompté par
`quotaService.recordSandboxSeconds`).

L'API expose déjà la capacité : `POST /v1/sessions/{id}/events` avec `{"events":[{"type":"user.interrupt"}]}`.
La session **continue jusqu'à une frontière sûre**, puis passe `idle` — ce n'est pas un `kill`, aucun
état corrompu. Provider-First : on **relaie**, on ne réimplémente pas d'arrêt côté Gateway (tuer le flux
SSE laisserait la sandbox tourner et facturer dans le vide).

---

## Comportement attendu

### Cas nominal

1. Un run est en cours sur le workspace (session persistante F-30 SF-30-04, flux SSE ouvert).
2. L'utilisateur appelle `POST /workspaces/{id}/agent/interrupt`.
3. `requireOwned` d'abord (isolation `user_id`), puis relais de `user.interrupt` au fournisseur ;
   la session est marquée « interruption demandée ». Réponse `204`, immédiate.
4. Le fournisseur amène la session à une frontière sûre puis émet `session.status_idle` : la boucle
   `awaitCompletion` du run **sort normalement** (jamais un échec ni un timeout).
5. Le tour est **persisté** avec sa transcription partielle et marqué `interrupted` (décision D2 du
   cadrage), et sa **consommation est décomptée** normalement (D3) — elle a réellement eu lieu.
6. L'événement SSE `done` porte `interrupted: true` : l'écran affiche le tour comme interrompu, sans
   le retirer.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Workspace inexistant ou appartenant à un autre utilisateur | `404 not_found` — aucun appel fournisseur |
| Aucune session en cours pour ce workspace | `409 no_active_session` — il n'y a rien à interrompre |
| Le fournisseur refuse l'interruption (session morte, panne) | `502 provider_error`, et la marque « interruption demandée » est retirée (le tour ne sera pas faussement affiché comme interrompu) |
| Non authentifié | `401` |
| Interruption demandée alors qu'aucun run n'est en vol (session `idle`) | `204` ; la marque est **consommée à l'ouverture du tour suivant**, qui n'est donc pas affiché comme interrompu |

---

## Critères d'acceptation

- [ ] `POST /workspaces/{id}/agent/interrupt` relaie `user.interrupt` à la session et renvoie `204`
- [ ] `requireOwned` est appelé **avant tout appel fournisseur** ; workspace d'un autre utilisateur ⇒ `404`
- [ ] Aucune session en cours ⇒ `409 no_active_session`, **aucun** appel fournisseur
- [ ] Un run interrompu se termine par le chemin nominal (`done`), jamais par `session_timeout` ni `error`
- [ ] Le tour interrompu est **persisté** (transcription partielle) et marqué `interrupted`
- [ ] La consommation du tour interrompu est décomptée (quota tokens + plafond bac à sable)
- [ ] L'événement SSE `done` porte `interrupted` (champ **additif** : un client qui l'ignore se comporte comme avant)
- [ ] Un échec de relais laisse la session **non marquée** (`provider_error`)
- [ ] Aucune clé d'API ni donnée utilisateur journalisée sur ce chemin

---

## Périmètre

### Hors scope

- Interruption **automatique** sur seuil de coût ou de durée
- **Reprise** d'un run interrompu là où il s'est arrêté
- Bouton et rendu « interrompu » dans l'écran → **SF-32-02**
- Interruption du mode Assistant (Phase 1) : il n'y a pas de session longue à interrompre

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Corps de requête | Aucun (endpoint sans charge utile) |
| Idempotence | Deux interruptions successives sur le même run ⇒ deux `204` ; l'événement est simplement relayé deux fois |
| Marque d'interruption | Portée par la **session** (identifiant fournisseur), consommée par le tour en vol ; remise à zéro à l'ouverture de chaque tour |
| Détection de repli | Une `stop_reason` de fin de tour contenant `interrupt` (insensible à la casse) marque aussi le tour comme interrompu (cas multi-instance) |
| Transcription | Sérialisée même si aucune commande n'a été lancée, dès lors que le tour est interrompu (sinon la marque serait perdue) |

---

## Technique

### Contrat API (figé — importé tel quel par SF-32-02)

| Méthode | Chemin | Corps | Réponse |
|---------|--------|-------|---------|
| `POST` | `/api/workspaces/{id}/agent/interrupt` | *(vide)* | `204 No Content` |

Erreurs (corps `{"error": "...", "message": "..."}`) :

| Code HTTP | `error` | Sens |
|-----------|---------|------|
| `401` | `unauthorized` | Non authentifié |
| `404` | `not_found` | Workspace inconnu ou non possédé (code d'erreur commun de l'Atelier) |
| `409` | `no_active_session` | Aucune exécution en cours |
| `502` | `provider_error` | Le fournisseur a refusé l'interruption |

Évolution **additive** du flux SSE `POST /api/workspaces/{id}/agent/stream`, événement `done` :

```json
{ "reply": "…", "changedFiles": [], "inputTokens": 0, "outputTokens": 0,
  "activeSeconds": 0, "interrupted": true }
```

Évolution **additive** du document `terminal` de l'historique (`GET /api/workspaces/{id}/chat`) :
champ booléen `interrupted` au même niveau que `blocks` / `omittedBlocks` / `inputTokens`.

### Tables impactées / Migration

**Aucune.** La marque d'interruption d'un tour vit dans le document `terminal_json` déjà existant
(donnée d'affichage, F-30 SF-30-09) — pas de colonne, pas de migration Liquibase.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/agent/ManagedAgentProvider.java` | `interruptSession(String sessionId)` |
| `atelier/agent/AnthropicManagedAgentProvider.java` | Relais `user.interrupt` (POST events) |
| `atelier/agent/AtelierSessionService.java` | `interruptSession(userId, workspaceId)` + marquage/consommation du tour |
| `atelier/agent/AtelierSessionResult.java` | Champ `interrupted` (constructeurs de compatibilité conservés) |
| `atelier/AtelierAgentController.java` | Endpoint `POST /agent/interrupt` + `interrupted` dans `done` |

---

## Plan de test

### Tests unitaires

- [ ] `interruptSession` : `requireOwned` d'abord ; workspace d'un autre utilisateur ⇒ 404, `verifyNoInteractions(provider)`
- [ ] Aucune session ⇒ `NoActiveSessionException`, aucun appel fournisseur
- [ ] Session en cours ⇒ `provider.interruptSession(sessionId)` appelé une fois
- [ ] Échec fournisseur ⇒ exception propagée **et** session non marquée (le tour suivant n'est pas `interrupted`)
- [ ] Un run dont la session a été marquée renvoie `interrupted=true`, avec réponse et fichiers resynchronisés
- [ ] Un run interrompu **persiste** le tour (USER + ASSISTANT) et le document porte `interrupted: true`
- [ ] Un run interrompu **décompte** l'usage (quota + secondes de bac à sable)
- [ ] Marque posée hors run ⇒ le tour suivant n'est **pas** marqué (consommation à l'ouverture du tour)
- [ ] `stop_reason` contenant `interrupt` ⇒ tour marqué même sans marque locale
- [ ] `AnthropicManagedAgentProvider.interruptSession` poste `{"events":[{"type":"user.interrupt"}]}` sur `/v1/sessions/{id}/events`

### Tests d'intégration

- [ ] `POST /workspaces/{id}/agent/interrupt` ⇒ `204` (utilisateur propriétaire)
- [ ] Sans authentification ⇒ `401`
- [ ] Workspace d'un autre utilisateur ⇒ `404 not_found`
- [ ] Aucune session ⇒ `409 no_active_session`
- [ ] Échec fournisseur ⇒ `502 provider_error`
- [ ] Le flux SSE `done` porte `interrupted`

### Isolation utilisateur

- [x] **Applicable** — `requireOwned(userId, workspaceId)` en première instruction, avant toute
  résolution de session et tout appel réseau. L'identifiant de session n'est jamais accepté du client :
  il est lu sur le workspace possédé.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement du Principal ni du mode d'authentification. Endpoint sous `/workspaces/**`, déjà couvert par la configuration de sécurité existante. |
| Contexte tenant | **Oui** | Nouvel endpoint accédant à une donnée de workspace. Composants vérifiés : `AtelierAgentController` (`currentUser.requireId()` seul, aucun `userId` accepté du client), `AtelierSessionService.interruptSession` (`workspaceService.requireOwned` en premier), `AnthropicManagedAgentProvider` (ne reçoit qu'un identifiant de session déjà résolu). Aucun autre composant ne résout de tenant sur ce chemin. |
| Plans / limites | **Oui** | L'interruption **ne consomme rien** : aucun pré-vol quota (refuser une interruption faute de quota enfermerait l'utilisateur dans le run qu'il veut arrêter). Le tour interrompu reste décompté par le chemin habituel (`recordSessionUsage`), donc les gates existants sont inchangés. Appels vérifiés : `QuotaService.assertWithinQuota`, `assertWithinSandboxLimit`, `recordUsage`, `recordSandboxSeconds` — aucun n'est modifié. |
| Navigation / routing | **Non** | Aucun écran, aucune route frontend (SF-32-02). |

---

## Dépendances

- **F-30 SF-30-04** (session persistante par workspace) — la session à interrompre.
- **F-30 SF-30-09** (persistance des tours) — support de la marque `interrupted`.

---

## Notes et décisions

- **Relayer, pas tuer** (Provider-First) : couper le flux SSE côté Gateway laisserait la sandbox
  tourner et facturer sans que personne ne regarde. `user.interrupt` arrête le travail à sa source.
- **Coordination par la session, pas par un état partagé** : le run tourne sur le pool SSE,
  l'interruption arrive sur un thread de requête. Aucun des deux ne signale l'autre : le fournisseur
  émet `session.status_idle`, et `awaitCompletion` sort par son chemin nominal. La seule mémoire
  partagée est une **marque d'affichage** (est-ce que ce tour a été interrompu ?), pas un mécanisme
  d'arrêt.
- **Le tour interrompu est conservé et facturé** (D2/D3 du cadrage) : il a réellement consommé du
  bac à sable et produit des sorties. Les effacer contredirait ce que l'écran vient d'afficher, et ne
  pas les décompter fausserait la facturation. Écart assumé avec SF-30-09, qui ne persiste que les
  runs aboutis — un run interrompu **a abouti**, plus tôt que prévu.
- **Repli sur `stop_reason`** : la marque en mémoire est locale à l'instance. Avec plusieurs répliques,
  l'interruption peut arriver sur une autre instance que le run ; la raison d'arrêt rapportée par le
  fournisseur sert alors de second signal. Les deux ensemble, jamais l'un à la place de l'autre.
- **Pas de pré-vol quota sur l'interruption** : c'est l'action qui *réduit* la consommation.
