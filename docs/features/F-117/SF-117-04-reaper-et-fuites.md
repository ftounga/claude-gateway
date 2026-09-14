# Mini-spec — F-117 / SF-117-04 Nettoyage des sessions hébergées et fuites mémoire

## Identifiant

`F-117 / SF-117-04`

## Feature parente

`F-117` — Le contexte d'un terminal ne déborde jamais

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-117-04-reaper-fuites`

---

## Objectif

> En une phrase : ce que l'audit a aussi trouvé — un **reaper** qui expire par âge les sessions
> Managed Agents orphelines (et **termine** la session fournisseur), plus la fermeture de **deux
> fuites mémoire** (`McpRateLimiter.hits` et le `Set` interne de `AtelierSessionService.syncedOutputs`).

---

## Comportement attendu

### Cas nominal

1. **Reaper** : un `@Scheduled` (`ManagedAgentSessionReaper`) balaie périodiquement les workspaces
   portant une session ouverte (`agentSessionId` non nul) dont l'ouverture (`agentSessionStartedAt`,
   déjà stocké mais **jamais lu** jusqu'ici) dépasse un âge maximal configurable. Pour chacun, il
   **oublie la session** — `forgetSession` **termine** désormais la session fournisseur (best-effort),
   purge les états en mémoire, et efface l'identifiant : plus de conteneurs orphelins.
2. **Fuite `McpRateLimiter.hits`** : la carte par jeton personnel est purgée de ses fenêtres périmées
   au-delà d'un seuil de jetons suivis (motif copié de `HelpRateLimiter.evictStale`).
3. **Fuite `syncedOutputs`** : le `Set` interne par session (identifiants de sorties déjà rapatriées)
   est **borné** ; au-delà du plafond, il est purgé (le resync reste idempotent : un contenu identique
   n'est pas réécrit).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Terminaison fournisseur en échec (session déjà morte) | Best-effort : l'identifiant est effacé quand même, le balayage continue |
| Reaper désactivé (`app.atelier.agent.reaper.enabled=false`) | Aucun balayage |
| Aucune session âgée | Balayage sans effet |
| Purge d'une fenêtre de débit vide | Entrée retirée de la carte |

---

## Critères d'acceptation

- [ ] Le reaper **expire une session âgée** : `forgetSession` termine la session fournisseur
      (best-effort) et efface `agentSessionId` + `agentSessionStartedAt`.
- [ ] Une session **récente** (sous l'âge max) n'est pas touchée.
- [ ] Une terminaison fournisseur en échec n'empêche pas d'oublier la session ni de continuer.
- [ ] `McpRateLimiter` **purge** les fenêtres périmées au-delà du seuil (la carte ne grossit plus sans
      borne) ; le débit reste correct (60/min).
- [ ] Le `Set` de `syncedOutputs` est **borné** : au-delà du plafond, il est purgé.
- [ ] Isolation : le reaper est un traitement système (tous tenants), mais chaque session reste
      rattachée à son workspace ; aucun mélange de données entre utilisateurs.

---

## Périmètre

### Hors scope (explicite)

- La compaction (SF-117-01), le repli sur 400 (SF-117-02), le nouveau départ (SF-117-03).
- Toute refonte de `resetSession` (fin de vie explicite d'une session, conservée telle quelle).

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| `app.atelier.agent.reaper.max-age` | Durée ISO-8601 ; défaut `PT24H` |
| `app.atelier.agent.reaper.cron` | cron ; défaut `0 20 * * * *` (toutes les heures) |
| `app.atelier.agent.reaper.enabled` | booléen ; défaut `true` |
| `McpRateLimiter` seuil de purge | constante (1000 jetons suivis), comme `HelpRateLimiter` |
| `syncedOutputs` plafond | constante (10 000 identifiants par session) |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | SELECT (reaper) + UPDATE (oubli de session) | nouvelle requête dérivée sur `agent_session_started_at` |

### Migration Liquibase

- [ ] Non applicable (aucun schéma nouveau : `agent_session_started_at` existe déjà).

### Composants Angular

- Aucun (backend uniquement).

---

## Plan de test

### Tests unitaires

- [ ] `AtelierSessionServiceTest` — le reaper **expire une session âgée** (termine + efface) et
      **épargne une session récente**.
- [ ] `AtelierSessionServiceTest` — `boundedSyncedAdd` borne/purge le `Set` interne.
- [ ] `McpRateLimiterTest` — les fenêtres périmées sont purgées au-delà du seuil ; le débit reste
      correct.

### Tests d'intégration

- [ ] Contexte Spring démarre avec le reaper (bean planifié).

### Isolation

- [x] Applicable — le reaper opère par workspace (session rattachée à son workspace) ; aucune donnée
      croisée entre utilisateurs.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (indépendante ; livrée en dernier par ordre du cadrage).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — `forgetSession` termine désormais le fournisseur.** C'est ce qui évite les conteneurs
  orphelins quand le reaper oublie une session âgée ; best-effort (une session déjà morte est
  effacée quand même). L'appel existant de `forgetSession` (publication sur session morte) n'en est
  pas affecté (terminer une session déjà morte est un no-op best-effort).
- **D2 — Config par `@Value`, pas dans le record.** Le reaper porte ses réglages en `@Value` pour ne
  pas toucher au constructeur d'`AtelierAgentProperties` (8 appelants).
- **D3 — Purge idempotente de `syncedOutputs`.** Le registre incrémental est déjà documenté
  idempotent : purger le `Set` fait au pire re-rapatrier un contenu identique, que la comparaison de
  contenu n'écrit pas.
