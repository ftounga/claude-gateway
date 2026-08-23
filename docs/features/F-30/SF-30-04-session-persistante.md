# Mini-spec — [F-30 / SF-04] Session persistante par workspace

---

## Identifiant

`F-30 / SF-04`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`ready`

## Date de création

2026-08-24

## Branche Git

`feat/SF-30-04-session-persistante`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Conserver **une session sandbox par workspace** d'un message à l'autre, pour que le système de
fichiers et l'historique survivent — `npm install` une fois, les tests réutilisent l'installation.

---

## Contexte

ADR-014 révise ADR-013 : la session était créée **par message** puis détruite (`finally
terminateSession`). Chaque message repartait d'une sandbox vierge. La justification économique de
cet éphémère était erronée — le runtime est mesuré en `active_seconds` (« temps avec ≥ 1 thread en
exécution »), donc **une session `idle` n'est pas facturée**, et une session en pause conserve sa
sandbox et son historique.

Trois pièges de conception en découlent, traités ici :

1. **Le décompte de consommation** : `getSessionUsage` renvoie le **cumul depuis le début de la
   session**. Le recréditer tel quel à chaque tour surfacturerait l'utilisateur de façon croissante.
2. **Le montage des fichiers** : remonter les fichiers du workspace à chaque tour **écraserait** le
   travail fait dans la sandbox au tour précédent.
3. **La fin de vie** : sans `finally`, plus rien ne termine la session — il faut un moyen explicite.

---

## Comportement attendu

### Cas nominal

1. **Premier message** d'un workspace : les fichiers sont montés, une session est créée, son
   identifiant est **persisté** sur le workspace.
2. **Messages suivants** : le message est envoyé **dans la même session**, **sans remonter** les
   fichiers — la sandbox a déjà son état, et le tour précédent l'a fait évoluer.
3. Après chaque tour, les sorties sont resynchronisées vers le workspace (**resync incrémental**) :
   seules les sorties **non encore rapatriées** de cette session sont réécrites et signalées comme
   modifiées.
4. Le décompte de consommation crédite le **delta** depuis le relevé précédent, jamais le cumul.
5. `DELETE /workspaces/{id}/agent/session` termine la session et efface son identifiant : le message
   suivant repart d'une sandbox neuve.
6. La suppression d'un workspace termine sa session (best-effort) : pas de sandbox orpheline.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Session persistée **terminée ou inconnue** côté fournisseur | **Reprise transparente** : une nouvelle session est créée (fichiers remontés), le message est rejoué **une fois**, l'identifiant est remplacé |
| Échec de la seconde tentative | Erreur propagée normalement (`error` dans le flux) — pas de boucle de reprise |
| Échec de `terminateSession` à la réinitialisation | Best-effort : l'identifiant est effacé quand même, sinon le workspace resterait collé à une session morte |
| Échec du relevé d'usage | Inchangé : best-effort, avalé — le run est déjà livré |
| Workspace d'un autre utilisateur | `requireOwned` **en premier** : 404, aucun appel fournisseur |
| Flag Phase 2 désactivé | Inchangé : refus **avant tout appel réseau** |

---

## Critères d'acceptation

- [ ] L'identifiant de session est persisté sur le workspace (migration Liquibase, Postgres **et** H2)
- [ ] Deux messages successifs sur un workspace n'ouvrent **qu'une** session (vérifié par test)
- [ ] Les fichiers ne sont **pas remontés** lors de la réutilisation d'une session existante
- [ ] Une session persistée invalide déclenche **une** reprise (nouvelle session + message rejoué), pas davantage
- [ ] La consommation créditée est le **delta** (tokens et secondes) depuis le relevé précédent, jamais le cumul
- [ ] Un relevé inférieur au précédent (session neuve) ne crédite **jamais** de valeur négative
- [ ] Le resync ne réécrit pas à chaque tour les sorties déjà rapatriées de la même session
- [ ] `DELETE /workspaces/{id}/agent/session` termine la session, efface l'identifiant, et est protégé par `requireOwned`
- [ ] La suppression d'un workspace termine sa session (best-effort, n'empêche jamais la suppression)
- [ ] L'isolation `user_id` et le pré-vol quota/flag restent **inchangés** et **en premier**
- [ ] Les événements SSE existants (`agent`, `action`, `action_result`, `status`, `done`, `error`) sont inchangés

---

## Périmètre

### Hors scope

- **Bouton « Réinitialiser la sandbox » dans l'écran** → **SF-30-06 (planifiée)**. Cette subfeature
  livre l'endpoint ; l'UI qui l'appelle suit immédiatement.
- Compteur de tokens dans l'indicateur d'activité → SF-30-05
- Expiration automatique par inactivité : la durée de vie maximale d'une session **n'est pas
  documentée** (ADR-014 ⚠️). La reprise transparente couvre le cas par conception ; une expiration
  programmée serait un garde-fou supplémentaire, pas un prérequis.
- Partage d'une session entre plusieurs workspaces ou plusieurs utilisateurs : jamais.

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Portée d'une session | **Un workspace** (donc un utilisateur). Jamais partagée. |
| Reprise | **Une seule** nouvelle tentative par run |
| Delta d'usage | `max(0, courant − précédent)` sur tokens entrée/sortie et secondes |
| Sorties déjà rapatriées | Mémorisées par session ; un redémarrage d'instance peut en réécrire une fois (idempotent) |
| Migration | `040-workspaces-agent-session.xml`, réversible, Postgres **et** H2 |

---

## Technique

### Endpoint(s)

| Méthode | Chemin | Rôle |
|---------|--------|------|
| `DELETE` | `/workspaces/{id}/agent/session` | Termine la session du workspace et efface son identifiant (204) |

### Tables impactées / Migration

`workspaces` — migration **040** : `agent_session_id` (varchar), `agent_session_started_at`
(timestamptz), `agent_input_tokens` / `agent_output_tokens` / `agent_active_seconds` (bigint, défaut
0) pour le calcul du delta. Toutes nullables ou à défaut : **aucune donnée existante cassée**.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `db/changelog/migrations/040-workspaces-agent-session.xml` | Migration (Postgres + H2) |
| `atelier/Workspace.java` | + colonnes de session |
| `atelier/agent/AtelierSessionService.java` | Réutilisation, reprise, resync incrémental, delta d'usage, `resetSession` |
| `atelier/AtelierAgentController.java` | + `DELETE /workspaces/{id}/agent/session` |
| `atelier/WorkspaceService.java` | Terminaison de session à la suppression d'un workspace |

---

## Plan de test

### Tests unitaires

- [ ] Deux runs successifs → **une seule** `createSession`, et **aucun** `uploadFile` au second
- [ ] Session persistée invalide → une nouvelle session, message rejoué **une** fois, identifiant remplacé
- [ ] Échec de la reprise → exception propagée, pas de troisième tentative
- [ ] Delta d'usage : second relevé cumulé → seul l'écart est crédité
- [ ] Relevé inférieur au précédent → crédit `0`, jamais négatif
- [ ] Resync incrémental : une sortie déjà rapatriée n'est pas réécrite au tour suivant
- [ ] `resetSession` : termine, efface l'identifiant ; échec de terminaison → identifiant effacé quand même
- [ ] Flag off / quota dépassé → refus **avant** toute création ou réutilisation de session

### Tests d'intégration

- [ ] `DELETE /workspaces/{id}/agent/session` → 204 et identifiant effacé
- [ ] Migration 040 appliquée sur base propre (H2), colonnes présentes

### Isolation utilisateur

- [x] **Applicable** — `DELETE /workspaces/{id}/agent/session` sur le workspace d'un autre
  utilisateur → 404, **aucun appel fournisseur** (`verifyNoInteractions`). La session est portée par
  le workspace, lui-même filtré par `user_id` : aucune session n'est jamais accessible hors de son
  propriétaire.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement d'authentification ; le nouvel endpoint suit exactement le patron des endpoints workspace existants. |
| Contexte tenant | **Oui** | La session devient un **état persisté** rattaché au workspace. Composants vérifiés : `AtelierSessionService` (`requireOwned` en premier, inchangé), `AtelierAgentController` (nouvel endpoint, `requireOwned` avant tout appel fournisseur), `WorkspaceService` (suppression → terminaison), `WorkspaceRepository` (accès par `id` **et** `user_id`). Aucun autre composant ne lit cet état. |
| Plans / limites | **Oui** | Le décompte passe du cumul au **delta**. Composants vérifiés : `QuotaService.recordUsage` et `recordSandboxSeconds` (signatures inchangées, seules les valeurs transmises changent), pré-vol `assertWithinQuota` / `assertWithinSandboxLimit` (inchangés, toujours **avant** toute session). Aucun autre appelant de ces méthodes n'est modifié. |
| Navigation / routing | **Non** | Un endpoint API ajouté, aucune route d'écran. |

---

## Dépendances

- Aucune bloquante. SF-30-06 (bouton de réinitialisation) dépend de celle-ci.

---

## Notes et décisions

- **Delta plutôt que cumul** : c'est le point qui touche à l'argent. Une session persistante rend
  `getSessionUsage` cumulatif ; recréditer ce cumul à chaque tour ferait payer plusieurs fois la même
  consommation, de plus en plus cher à mesure que la session vit.
- **Ne pas remonter les fichiers à la réutilisation** : la sandbox porte désormais l'état de vérité du
  tour précédent. Remonter la version S3 écraserait ce que l'agent vient de faire.
- **Reprise unique** : une session invalide se répare en en ouvrant une neuve. Boucler au-delà d'une
  tentative masquerait une panne réelle du fournisseur.
- **Fin de vie explicite** : ADR-014 signale qu'une sandbox longue-vie détenant l'état d'un projet
  accroît la valeur d'une évasion. L'endpoint de réinitialisation et la terminaison à la suppression
  du workspace sont la contrepartie de l'abandon du `finally`.
