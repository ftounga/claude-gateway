# Mini-spec — [F-174 / SF-174-01] La mesure de départ

## Identifiant

`F-174 / SF-174-01`

## Feature parente

`F-174` — La carte qui répond (cadrage `CADRAGE-F-174-la-carte-qui-repond.md`, D1→D10 validées le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-174-01-mesure-de-depart`

---

## Objectif

Compter, à chaque tour d'un poste qui a une carte, ce que la gateway joint à l'agent depuis la carte (nombre de faits, caractères, fichiers sources, pièges, échéances), et documenter la requête qui compte les fouilles de la carte par l'agent, pour disposer d'un point de départ avant l'index.

---

## Comportement attendu

### Cas nominal

1. À chaque appel de `HostMapKnowledgeProvider.factsFor` sur un projet rattaché à un poste, une ligne `host_map_lookups` est écrite : `kind = TURN`, `strategy = LEXICAL` (recherche F-137 actuelle), ou `NONE` si rien n'a été joint, avec `facts_count`, `chars`, `pitfalls_count`, `deadlines_count`, `sources` (fichiers cités, dans l'ordre).
2. Le bloc rendu à l'agent est **strictement inchangé** (même texte, même place : la consigne du tour).
3. Aucun contenu (question, faits) n'est rangé : seulement des compteurs.
4. Les lignes partent avec le compte (`AccountService`) et avec le poste (`GovernanceHostLifecycleListener`) via `HostMapIndexPurge` (D10).

### Requête de référence (fouilles de la carte par l'agent)

```sql
-- Fouilles de la carte par l'agent, par jour, sur une période (PostgreSQL)
SELECT date_trunc('day', a.created_at) AS jour, a.tool, count(*) AS fouilles
FROM runner_audit a
WHERE a.user_id = :userId
  AND a.created_at >= :from AND a.created_at < :to
  AND a.tool IN ('bash', 'read_file', 'grep')
  AND EXISTS (SELECT 1 FROM host_map_files f
              WHERE f.user_id = a.user_id AND f.host_id = a.host_id
                AND a.target LIKE '%' || f.path || '%')
GROUP BY 1, 2 ORDER BY 1, 2;

-- Ce que la gateway a joint, par jour
SELECT date_trunc('day', created_at) AS jour, kind, strategy, count(*) AS consultations,
       avg(facts_count) AS faits_moyens, avg(chars) AS caracteres_moyens,
       sum(pitfalls_count) AS pieges, sum(deadlines_count) AS echeances
FROM host_map_lookups
WHERE user_id = :userId AND created_at >= :from AND created_at < :to
GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;
```

SF-174-07 expose ces deux mesures en API (avant / après).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Projet sans poste (« Hébergé ») | Rien n'est journalisé, rien n'est joint (inchangé) |
| Base indisponible à l'écriture du journal | Journal perdu pour ce tour, le tour continue (jamais d'exception) |
| Bloc vide | Ligne `NONE`, compteurs à 0 |

---

## Critères d'acceptation

- [x] Un tour sur un poste à carte écrit une ligne `TURN` avec les compteurs exacts du bloc joint.
- [x] Le bloc joint est identique à celui d'avant SF-174-01.
- [x] Un échec d'écriture du journal ne remonte pas au tour.
- [x] Isolation : lecture par `user_id` ; purge du poste limitée à `(user_id, host_id)` ; purge du compte limitée à `user_id`.

---

## Périmètre

### Hors scope (explicite)

- L'API de comparaison avant / après : SF-174-07.
- Le comptage des appels `carte_chercher` : branché en SF-174-05 (même table, `kind = TOOL`).
- Aucun écran.

---

## Technique

### Tables

- **Nouvelle** `host_map_lookups` (migration `138-host-map-lookups.xml`) : `id`, `user_id`, `host_id`, `workspace_id`, `kind`, `strategy`, `facts_count`, `chars`, `pitfalls_count`, `deadlines_count`, `sources`, `created_at` ; index `(user_id, host_id, created_at)`.

### Composants backend

- `governance/map/index/HostMapLookup`, `HostMapLookupRepository`, `HostMapLookupJournal`, `HostMapIndexPurge`.
- `governance/map/HostMapKnowledgeProvider` (journalisation après calcul du bloc).
- `account/AccountService` (mutateur `setHostMapIndexPurge`), `governance/GovernanceHostLifecycleListener` (mutateur `setMapIndexPurge`) : injection par mutateur pour ne pas changer les constructeurs.

### Endpoints / écrans

Aucun.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Contexte tenant | Oui | `HostMapKnowledgeProvider` résout le poste par `GovernanceHostScope.hostOf(userId, workspaceId)` (inchangé) ; le journal reçoit ce `(user_id, host_id)` |
| Auth, plans, routing | Non | — |

---

## Plan de test

- [x] `HostMapLookupJournalTest` : compteurs (faits, caractères, pièges, échéances, sources), `NONE` sur bloc vide, rien sans propriétaire, jamais d'exception.
- [x] `HostMapKnowledgeProviderJournalTest` : le tour est journalisé et le bloc rendu inchangé ; pas de poste ⇒ pas de journal.
- [x] `HostMapLookupIsolationIntegrationTest` (H2, Liquibase réel) : lecture isolée par `user_id`, purges par poste et par compte bornées.
- [x] Suite complète verte (démarrage du contexte).

## Dépendances

Aucune.

## Notes et décisions

- **Arbitrage (réversible)** : écriture synchrone d'une ligne par tour (insert simple, quelques ms) plutôt qu'asynchrone : la mesure doit être complète pour être comparable, et un insert raté est avalé.
- **Arbitrage (réversible)** : les fouilles de l'agent ne sont pas recopiées ; `runner_audit` les porte déjà (cible = commande ou chemin). Une seule vérité.
