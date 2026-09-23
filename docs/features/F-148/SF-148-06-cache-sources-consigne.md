# Mini-spec — F-148 / SF-148-06 Cache gateway des sources de la consigne (CLAUDE.md + skills + STATE/PLAN)

> Base : `project-governance/templates/subfeature-template.md`

---

## Identifiant

`F-148 / SF-148-06`

## Feature parente

`F-148` — Performance du raisonnement (affinages)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-148-06-cache-sources-consigne`

---

## Objectif

> En une phrase.

Mettre en cache côté gateway, par `(user_id, host_id, workspace_id, chemin)` avec empreinte SHA-256 et
rafraîchissement asynchrone throttlé (modèle exact de `HostMapStore`), le contenu des fichiers que
`buildSystemPrompt` relit à **chaque** message sur le runner (`CLAUDE.md`, `STATE.md`/`PLAN-ACTION.md`
du sujet, arborescence + fichiers de skills), pour les servir depuis la base au lieu du runner.

---

## Comportement attendu

### Cas nominal

1. **Amorçage (tour 1 d'un workspace, cache vide)** : `buildSystemPrompt` ne trouve rien en base
   (`isPrimed == false`) → il lit **en direct** via le runner (comportement actuel, à l'octet près),
   puis planifie un rafraîchissement post-tour.
2. **Post-tour** : `PromptSourceCache.refreshAfterTurn` part dans un pool dédié (jamais sur le chemin
   critique), **throttlé ~30 s par workspace** : il liste l'arborescence, lit `CLAUDE.md`,
   `STATE.md`, `PLAN-ACTION.md`, et les fichiers de skills du catalogue, et range chacun en base avec
   son empreinte. Un contenu inchangé (même empreinte) n'est pas réécrit (seule `observed_at` bouge).
   Une absence est rangée comme telle (marqueur négatif) pour ne pas relire un fichier absent à chaque
   tour.
3. **Tours suivants (cache amorcé)** : `buildSystemPrompt` lit **depuis la base** (rapide), sans
   aucun aller-retour runner d'amorçage. Le contenu servi est **byte-identique** à la lecture directe
   tant que l'empreinte ne change pas → le préfixe reste stable → cache de prompt (F-134) préservé.
4. **Cible `SANDBOX`** : aucun changement — les fichiers vivent dans le stockage objet (pas
   d'aller-retour runner), on continue à lire via `readOptional`/`safeTree`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Machine muette / runner injoignable pendant le refresh | Rien n'est détruit : la copie précédente reste et sert (repli passant, comme `HostMapStore`) |
| Magasin (DB) en panne à la lecture | Repli passant : tour rendu sans le fichier caché plutôt qu'un tour raté |
| Rafales de tours sur le même workspace | Un seul refresh par fenêtre de 30 s (throttle) — la machine du client n'est pas martelée |
| Fichier absent sur la machine | Marqueur négatif rangé → pas de relecture runner à chaque tour |
| Pool de refresh saturé | Tâche abandonnée en silence (`DiscardPolicy`) — le prochain tour relira |

---

## Critères d'acceptation

- [ ] Une nouvelle table `prompt_source_files` existe (migration `126-...`), avec unicité
      `(user_id, workspace_id, path)`, index `(user_id, workspace_id)`, colonne `host_id`, `content`,
      `digest`, `observed_at`, réversible, sans clé étrangère (même choix que `host_map_files`).
- [ ] `PromptSourceStore.refresh(...)` range le tree + `CLAUDE.md` + `STATE.md`/`PLAN-ACTION.md` +
      les skills, chacun avec son empreinte ; un contenu inchangé n'est pas réécrit.
- [ ] Deux refresh rapprochés (< 30 s) sur le même workspace ne lisent la machine qu'une fois.
- [ ] Une lecture runner qui échoue (injoignable) ne détruit aucune ligne existante.
- [ ] `buildSystemPrompt` sur cible `RUNNER`, cache amorcé, ne fait **aucun** aller-retour runner
      d'amorçage et produit un préfixe **byte-identique** à la lecture directe (digest stable).
- [ ] Toute lecture/écriture porte `user_id` **et** (`host_id` via) `workspace_id` — isolation
      cross-user : la copie d'un client ne peut être servie au tour d'un autre.
- [ ] Cible `SANDBOX` : comportement inchangé (lecture directe).
- [ ] La suppression de compte purge `prompt_source_files` (`deleteByUserId`).

---

## Périmètre

### Hors scope (explicite)

- Cache des lectures `read_file` **de l'agent** en cours de tour (ceci ne couvre que les lectures
  d'**amorçage** de la consigne système).
- Index de repo / recherche (SF-148-07) et mémoire de résolutions (SF-148-08).
- Toute modification de la sous-boucle explore ou des outils `grep`/`glob`.
- Cible `SANDBOX` : hors périmètre (pas d'aller-retour runner à supprimer).

---

## Technique

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `prompt_source_files` (nouvelle) | INSERT / SELECT / UPDATE / DELETE | copie DB throttlée par `(user_id, workspace_id, path)` |

### Migration Liquibase

- [x] Oui — `126-prompt-source-files.xml` (réversible via rollback implicite `createTable`).

### Composants impactés (préoccupations transversales)

- **Contexte tenant / isolation** : nouvelle table portant `user_id` + `host_id` + `workspace_id`.
  Composants qui résolvent/portent le tenant et sont touchés :
  - `PromptSourceStore` (nouveau) — toute requête filtre `user_id` + `workspace_id`.
  - `PromptSourceFileRepository` (nouveau) — aucune méthode sans le couple `(userId, workspaceId)`.
  - `AtelierChatService.buildSystemPrompt` — lit via le cache **uniquement** pour `isRunnerTarget()` ;
    `SANDBOX` inchangé. `runLoop` planifie le refresh post-tour (à côté de `hostKnowledge`).
  - `AccountService.deleteAccount` — purge `deleteByUserId` (comme `host_map_files`).
- **Cache de prompt (F-134)** : `buildSystemPrompt` est touché → contenu servi byte-identique tant que
  l'empreinte ne change pas ; injection toujours verbatim ; ordre du catalogue de skills inchangé
  (déterministe, jamais classé par la question). Test de stabilité byte-à-byte.
- **Auth / Principal** : inchangé.
- **Plans / limites** : inchangé (le refresh est borné et asynchrone ; pas de traitement synchrone).

---

## Plan de test

### Tests unitaires (`PromptSourceStoreTest`)

- [ ] range tree + CLAUDE.md + STATE/PLAN + skills, chacun avec empreinte
- [ ] contenu inchangé non réécrit (empreinte suffit ; `observed_at` bouge)
- [ ] deux refresh rapprochés → une seule lecture machine (throttle 30 s)
- [ ] runner injoignable → aucune ligne détruite
- [ ] absence rangée comme marqueur négatif (pas de relecture)
- [ ] lecture/tree portent toujours `user_id` + `workspace_id` ; `null` → vide

### Tests de service (`AtelierChatServicePromptSourceTest`)

- [ ] cible `RUNNER` + cache amorcé → aucun appel `readFile`/`listFiles` runner d'amorçage
- [ ] préfixe byte-identique entre lecture directe (non amorcé) et lecture cache (amorcé)
- [ ] cible `SANDBOX` → lecture directe, cache jamais consulté

### Isolation

- [x] Applicable — la copie du workspace A d'un user n'est jamais servie au workspace B ni à un autre
      user (filtre `(user_id, workspace_id)` obligatoire).

---

## Dépendances

### Subfeatures bloquantes

- `SF-148-05` — done (STATE/PLAN injectés via `readOptional`, que ce cache remplace sur runner).
- `SF-148-03` — done (`MAX_SKILLS_ANNOUNCED` = 15).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Écart assumé vs cadrage** : le cadrage dit « par `(host_id, chemin)` ». Or `CLAUDE.md`,
  `STATE.md`/`PLAN-ACTION.md` et les skills sont lus **relativement au `projectPath` du workspace**
  (`readOptional(userId, workspace, path)`), donc **différents d'un sujet à l'autre sur un même
  poste**. Une clé purement `(host_id, chemin)` servirait le `CLAUDE.md` du sujet A au sujet B.
  On clé donc par **workspace** (qui détermine le `projectPath` et la cible), en conservant `host_id`
  et `user_id` sur la ligne pour l'isolation et la purge. `HostMapStore` clé bien par `(user, host)`
  parce qu'il ne range que les fichiers `MAP` de la **racine** du poste — pas le même périmètre.
- **Amorçage sans régression** : tant que le cache d'un workspace n'est pas amorcé (`isPrimed`), on
  lit en direct (comportement actuel). Le tour 1 paie donc les allers-retours comme aujourd'hui ; les
  tours suivants les suppriment. Zéro régression sur le contenu de la consigne.
- **Aucun composant cluster** : stockage dans le Postgres existant, pool de threads borné en process.
