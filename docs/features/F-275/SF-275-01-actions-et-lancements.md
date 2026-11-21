# Mini-spec — [F-275 / SF-275-01] Les actions et leurs lancements (backend)

## Identifiant

`F-275 / SF-275-01`

## Feature parente

`F-275` — Actions rapides : lancer un workflow configuré d'un clic (cadrage `CADRAGE-F-275-actions-rapides.md`)

## Statut

`done`

## Date de création

2026-11-21

## Branche Git

`feat/SF-275-01-actions-rapides-api`

---

## Objectif

Servir, pour un terminal possédé, la liste fusionnée des actions rapides (déclencheurs des skills + actions personnalisées visibles ici) avec leur dernière exécution, et enregistrer un lancement.

---

## Comportement attendu

### Cas nominal

1. **Déclencheurs d'un skill** (`SkillTriggers.parse(contenu)`), lus dans l'en-tête `---` :
   - clé `declencheurs:` / `déclencheurs:` / `triggers:` — valeurs en ligne (`a, b`, `[a, b]`, `« a », « b »`) ou en liste YAML (`- a`) ;
   - sinon, dans `description:`, les segments entre guillemets (« … » ou "…") qui **suivent** un mot déclencheur (`déclench…`, `trigger…`, `quand on dit`, `à dire`) — ex. réel `Déclenché par « point hebdo », « suivi du jour » ou « prépare le comité ».` ⇒ 3 déclencheurs ;
   - clés facultatives `sujet:` (nom du sujet du poste) et `clic: envoyer|deposer`.
   - Au plus 5 déclencheurs par skill, 80 caractères chacun ; doublons retirés (casse ignorée).
   Le catalogue F-177 porte ces indications dans `SkillEntry.launch` (calculées sur le contenu **complet**, avant la coupe à 160 caractères de la description), sans lecture supplémentaire du poste.
2. `GET /api/workspaces/{id}/quick-actions?skills=true|false` (défaut `true`) → `[QuickActionView]` :
   - actions personnalisées visibles dans ce terminal : `scope=COMPTE`, ou `POSTE` et `host_id` = poste du terminal, ou `SUJET` et `workspace_id` = ce terminal — triées par libellé ;
   - puis (si `skills=true`) une action par déclencheur des skills du catalogue : libellé = déclencheur avec majuscule initiale, phrase = déclencheur, `skill` = nom du skill, sujet cible = `sujet:` résolu parmi les sujets du poste (absent ou introuvable ⇒ terminal courant), clic = `clic:` (défaut `DEPOSER`) ;
   - un déclencheur dont la phrase normalisée égale celle d'une action personnalisée visible est **masqué** ;
   - chaque vue porte `key`, `source` (`PERSO`|`SKILL`), `id` (perso), `label`, `phrase`, `skill`, `scope`, `targetWorkspaceId`, `targetName`, `targetMissing`, `clickMode`, `suggestDays` (1 = lundi … 7), `suggestMoment`, `lastRunAt`.
   - Terminal Teams ou fil Jarvis ⇒ liste vide.
3. `POST /api/workspaces/{id}/quick-actions` (créer), `PUT …/{actionId}` (modifier, champs fournis seulement), `DELETE …/{actionId}` (supprimer) — mêmes règles que l'outil SF-275-02 (`QuickActionService`).
4. `POST /api/workspaces/{id}/quick-actions/launches {key}` → upsert `quick_action_launches (user_id, action_key)` : `last_launched_at = now`, `last_workspace_id` = ce terminal, `launch_count + 1` ; renvoie `{key, lastRunAt}`.
5. Purge du compte : les deux tables, par `user_id`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Terminal d'autrui ou inexistant | `WorkspaceNotFoundException` | 404 |
| Action personnalisée d'autrui / inconnue (PUT, DELETE) | `QUICK_ACTION_NOT_FOUND` | 404 |
| Libellé ou phrase vide / trop long, portée inconnue, clic inconnu, jour hors 1-7, moment inconnu | `QUICK_ACTION_INVALID` + message | 400 |
| Portée POSTE depuis un terminal sans poste | `QUICK_ACTION_INVALID` | 400 |
| Sujet cible d'autrui / pas un sujet de ce poste | `QUICK_ACTION_INVALID` (« Aucun sujet … ») | 400 |
| Libellé déjà pris par une action visible ici | `QUICK_ACTION_CONFLICT` | 409 |
| Plafond 50 actions par compte | `QUICK_ACTION_INVALID` | 400 |
| Clé de lancement vide ou > 200 car. | `QUICK_ACTION_INVALID` | 400 |
| Poste muet (runner hors ligne) | les skills manquent, les actions perso restent | 200 |

---

## Critères d'acceptation

- [x] CA1 L'en-tête réel de `suivi-hebdo` donne exactement `point hebdo`, `suivi du jour`, `prépare le comité`.
- [x] CA2 `declencheurs: [a, b]`, liste YAML et `triggers: a, b` sont reconnus ; `sujet:` et `clic: envoyer` aussi.
- [x] CA3 `GET` rend actions perso (COMPTE, POSTE du terminal, SUJET du terminal) puis actions de skills ; une perso d'un autre poste/sujet n'apparaît pas.
- [x] CA4 Une perso de même phrase qu'un déclencheur masque ce déclencheur.
- [x] CA5 `skills=false` ne lit pas le catalogue (aucune lecture du poste).
- [x] CA6 `POST …/launches` puis `GET` ⇒ `lastRunAt` renseigné sur l'action (perso et skill).
- [x] CA7 Isolation : Bob reçoit 404 sur le terminal d'Alice (GET, POST, launches) et 404 sur l'action d'Alice (PUT/DELETE) ; Bob ne voit jamais une action COMPTE d'Alice.
- [x] CA8 Création invalide ⇒ 400 explicite ; libellé en doublon ⇒ 409.

---

## Périmètre

### Hors scope (explicite)

- L'outil agent (SF-275-02) ; l'écran (SF-275-03).
- Compter un lancement tapé à la main ; purge des actions à la suppression d'un sujet (une action orpheline n'est plus visible, sa cible manquante est signalée `targetMissing`).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| scope | POSTE si le terminal a un poste, sinon SUJET | défaut si absent |
| click_mode | DEPOSER | défaut |
| suggest_days / suggest_moment | null | pas de suggestion |
| user_id | utilisateur connecté | jamais lu de la requête |
| workspace_id / host_id | terminal du contexte / son poste | selon la portée |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| label | Oui | 60 | non vide | parmi les actions visibles ici (casse ignorée) | strip, espaces réduits |
| phrase | Oui | 2000 | non vide | Non | strip |
| scope | Non | — | SUJET, POSTE, COMPTE | — | majuscules |
| targetWorkspaceId | Non | — | sujet possédé ; si le terminal a un poste, sujet de ce poste | — | — |
| clickMode | Non | — | DEPOSER, ENVOYER | — | majuscules |
| suggestDays | Non | 7 valeurs | entiers 1..7 | — | triés, dédoublonnés |
| suggestMoment | Non | — | MATIN, APRES_MIDI, JOURNEE | — | majuscules ; jours sans moment ⇒ JOURNEE |
| key (lancement) | Oui | 200 | non vide | — | strip |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/workspaces/{id}/quick-actions` | Oui | USER (propriétaire) |
| POST | `/api/workspaces/{id}/quick-actions` | Oui | USER (propriétaire) |
| PUT | `/api/workspaces/{id}/quick-actions/{actionId}` | Oui | USER (propriétaire) |
| DELETE | `/api/workspaces/{id}/quick-actions/{actionId}` | Oui | USER (propriétaire) |
| POST | `/api/workspaces/{id}/quick-actions/launches` | Oui | USER (propriétaire) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `quick_actions` | INSERT / SELECT / UPDATE / DELETE | nouvelle ; filtre `user_id` |
| `quick_action_launches` | INSERT / UPDATE / SELECT | nouvelle ; unique `(user_id, action_key)` |

### Migration Liquibase

- [x] Oui — `275-01-quick-actions.xml` (rollback = drop des deux tables)

### Composants Angular (si applicable)

- Aucun (SF-275-03).

---

## Plan de test

### Tests unitaires

- [x] `SkillTriggersTest` — en-tête réel suivi-hebdo, `declencheurs:` en ligne / liste, `triggers:`, guillemets droits, sans mot déclencheur ⇒ rien, plafond 5, `sujet:` / `clic:`.
- [x] `SkillCatalogServiceTest` — `launch` calculé sur le contenu complet (description longue coupée, déclencheurs intacts).
- [x] `QuickActionServiceTest` — visibilité par portée, masquage du déclencheur, sujet du skill résolu / introuvable, validations, conflit de libellé, plafond, `skills=false` sans catalogue.

### Tests d'intégration

- [x] `QuickActionApiIntegrationTest` — création 201 → GET la rend ; PUT ; DELETE 204 ; 400 invalide ; 409 doublon ; lancement → `lastRunAt` ; skill déclaré dans un projet hébergé apparaît sans configuration.

### Isolation workspace

- [x] Applicable — Bob : 404 sur GET/POST/launches du terminal d'Alice, 404 sur PUT/DELETE de l'action d'Alice, et la portée COMPTE d'Alice n'apparaît pas dans le terminal de Bob.

---

## Dépendances

### Subfeatures bloquantes

- Aucune.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non concerné).

---

## Notes et décisions

- Pas de clé étrangère (même choix que `account_governance_rules`) : purge nommée dans `AccountService`.
- Clé d'une action : `c:<uuid>` (perso) ; `s:<ancre>:<skill>:<déclencheur normalisé>` (skill), l'ancre étant le poste (skill de poste) ou le terminal (skill du sujet) — stable tant que le skill garde son nom et son déclencheur.
