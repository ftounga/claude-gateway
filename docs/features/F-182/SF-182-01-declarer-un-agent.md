# Mini-spec — F-182 / SF-182-01 Déclarer un agent

## Identifiant

`F-182 / SF-182-01`

## Feature parente

`F-182` — Les agents nommés (cadrage `CADRAGE-F-182-les-agents-nommes.md`, décisions D1 et D3)

## Statut

`done`

## Date de création

2026-10-16

## Branche Git

`feat/SF-182-01-declarer-un-agent`

---

## Objectif

Lire, valider et cataloguer les agents nommés déclarés dans `.claude/agents/<nom>.md` (sujet puis poste), et
les montrer dans l'écran Gouvernance avec leur état (utilisable, avertissements, erreurs).

---

## Comportement attendu

### Cas nominal

1. Un fichier `.claude/agents/<nom>.md` (dans le **sujet** ou à la **racine du poste**) déclare un agent :

   ```markdown
   ---
   description: Relit un diff avant une MR et rend ses constats classés.
   outils: lecture            # lecture | ecriture | liste (read_file, grep, glob, Read, Edit, Bash…)
   modele: claude-sonnet-5    # facultatif ; défaut = modèle de la Forge (Opus)
   justification: relecture courte, coût d'abord   # obligatoire si `modele` est écrit (D3)
   ---
   Tu es le relecteur… (la consigne de l'agent)
   ```

   Les clés anglaises de Claude Code sont acceptées (`tools`, `model`) pour la parité ; les noms d'outils
   Claude Code (`Read`, `Grep`, `Glob`, `LS`, `Edit`, `MultiEdit`, `Write`, `Bash`) sont traduits vers les
   nôtres (`read_file`, `grep`, `glob`, `list_files`, `edit_file`, `multi_edit`, `write_file`, `bash`).
2. Le nom de l'agent est le **nom de fichier** (minuscules, chiffres, `-`, `_`, 64 max).
3. Un agent du **sujet masque** celui du poste de même nom (comme les skills, F-177).
4. `NamedAgentCatalogService.catalog(userId, workspace)` rend les agents (sujet puis poste), chacun **validé** :
   accès (`LECTURE`/`ECRITURE`), outils retenus, modèle effectif (ou `null` = modèle de la Forge),
   avertissements et erreurs. `load(userId, workspace, nom)` rend la définition complète (consigne comprise).
5. **Lecture frugale** : sur cible RUNNER amorcée, tout vient du cache de consigne (`prompt_source_files`) —
   aucun aller-retour poste par tour. Le rafraîchissement post-tour (déjà existant, throttlé 30 s) range
   désormais aussi les fichiers `.claude/agents/*.md` du sujet (≤ 20) et ceux de la racine du poste
   (index + contenus sous un chemin réservé `/__host_agents__…`).
6. `GET /api/workspaces/{workspaceId}/agents` (lecture seule, aucun tour) rend le catalogue du terminal.
7. L'écran Gouvernance, bloc « Ce qui s'applique vraiment » (F-177 / SF-177-04), gagne une section
   **Agents nommés (N)** : `/agent <nom>`, origine (poste / sujet), source (paquet / client), accès, modèle
   (« modèle de la Forge » ou l'identifiant écrit), et les problèmes en clair.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Consigne (corps) vide | Agent **non utilisable** : « consigne vide » | — (affiché) |
| Aucun outil reconnu dans `outils` | Agent **non utilisable** : « aucun outil reconnu » | — |
| Outil inconnu dans la liste | Ignoré + avertissement « outil inconnu : x » | — |
| `modele` écrit sans `justification` | Modèle **ignoré** (Opus par défaut, D3) + avertissement | — |
| `modele` mal formé | Modèle ignoré + avertissement | — |
| Consigne > 20 000 caractères | Tronquée + avertissement | — |
| Poste injoignable / fichier illisible | Agent absent du catalogue, jamais d'erreur levée | 200 |
| Terminal d'autrui | `requireOwned` → introuvable | 404 |
| Terminal Teams | Catalogue vide | 200 |

---

## Critères d'acceptation

- [ ] CA1 — Un agent du sujet et un agent du poste sont listés, sujet d'abord ; un homonyme du sujet masque le poste.
- [ ] CA2 — `outils: lecture` → accès LECTURE, outils = read_file, list_files, search_files, grep, glob ; `outils: ecriture` → ECRITURE avec en plus write_file, edit_file, multi_edit, bash ; liste explicite → outils traduits, accès ECRITURE dès qu'un outil d'écriture y figure. Sans `outils` → LECTURE (défaut sûr).
- [ ] CA3 — D3 : sans `modele` → modèle `null` (= Forge/Opus) ; `modele` + `justification` → retenu ; `modele` sans `justification` → ignoré + avertissement.
- [ ] CA4 — Consigne vide ou aucun outil reconnu → `usable=false` avec l'erreur.
- [ ] CA5 — Sur cible RUNNER amorcée, le catalogue est servi par le cache, sans `listFiles`/`readFile` runner.
- [ ] CA6 — Le refresh post-tour range les agents du sujet et ceux du poste ; un dossier d'agents du poste devenu vide ou absent efface l'index.
- [ ] CA7 — `GET /api/workspaces/{id}/agents` : 200 propriétaire, 404 autrui ; la consigne n'est jamais renvoyée.
- [ ] CA8 — L'écran Gouvernance liste les agents du poste et des sujets avec origine, source, accès, modèle et problèmes.
- [ ] CA9 — Aucun contenu d'agent n'est journalisé.

---

## Périmètre

### Hors scope (explicite)

- Déléguer à un agent (`explore`/`task` avec `agent`, `/agent <nom>`, annonce au modèle) → **SF-182-02**.
- Gabarits relecteur/testeur/auditeur-infra et création par `gouvernance_proposer(type: AGENT)` → **SF-182-03**.
- Édition d'un agent depuis l'écran (le fichier vit chez le client ; il se modifie sur le poste ou via F-177).
- Agents exécutés côté serveur, workflows multi-agents (cadrage §5).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| access | `LECTURE` | sans `outils` : le moindre privilège |
| model | `null` | `null` = modèle de la Forge (Opus), D3 |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| nom (fichier) | Oui | 64 | `[a-z0-9][a-z0-9_-]{0,63}` | par origine ; sujet masque poste | minuscules |
| description | Non | 160 affichés | texte ; défaut = 1re ligne de la consigne | — | strip |
| outils / tools | Non | — | `lecture`, `ecriture`/`écriture`, ou liste (virgules, `[a, b]` ou liste YAML) | — | alias Claude Code traduits |
| modele / model | Non | 100 | `[A-Za-z0-9][A-Za-z0-9._:/-]{1,99}` ; `inherit`/`defaut`/`opus` = défaut | — | strip |
| justification | Si `modele` | 300 | texte | — | strip |
| consigne (corps) | Oui | 20 000 | texte non vide | — | tronquée au-delà |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/workspaces/{workspaceId}/agents` | Oui (JWT) | USER, terminal possédé |
| GET | `/api/governance/hosts/{hostRef}/effective` (enrichi : `agents`) | Oui | accès Forge |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `prompt_source_files` | INSERT/UPDATE/DELETE | nouvelles lignes de cache (chemins `.claude/agents/…` et réservés `/__host_agents__…`), même clé `(user_id, workspace_id, path)` |

### Migration Liquibase

- [x] Non applicable (aucun changement de schéma).

### Composants

- Backend : `atelier/agents/NamedAgent`, `NamedAgentParser`, `NamedAgentEntry`, `NamedAgentCatalogService`,
  `NamedAgentController` ; `PromptSourceStore` (cache agents) ; `GovernanceEffectiveService`/`View` (section agents).
- Angular : `GovernanceEffectiveComponent` (section « Agents nommés »), `governance.models.ts`.

---

## Plan de test

### Tests unitaires

- [ ] `NamedAgentParserTest` — entête FR/EN, alias Claude Code, liste YAML, accès par défaut, D3 (justification), consigne vide, outil inconnu, troncature.
- [ ] `NamedAgentCatalogServiceTest` — sujet puis poste + masquage ; cache utilisé sans runner quand amorcé ; poste injoignable → vide ; Teams → vide.
- [ ] `PromptSourceStoreTest` — refresh range agents du sujet et index/contenus du poste ; index vidé quand le dossier disparaît.
- [ ] `GovernanceEffectiveServiceTest` — section agents (poste + sujets, source paquet/client).
- [ ] `governance-effective.component.spec.ts` — section Agents nommés rendue (accès, modèle, problème).

### Tests d'intégration

- [ ] `NamedAgentCatalogApiIntegrationTest` — 200 propriétaire (nom, accès, description, pas de consigne), 404 autrui.

### Isolation

- [x] Applicable — `requireOwned` sur le terminal ; cache lu sous `(user_id, workspace_id)` ; test 404 autrui.

---

## Dépendances

- F-177 (catalogue de skills, cache de consigne, écran « Ce qui s'applique vraiment ») — Terminée.
- Aucune question ouverte impactée.

## Préoccupations transversales

Aucune (cadrage §4) : ni auth, ni tenant, ni plans/limites, ni routing touchés.

---

## Notes et décisions

- **A1 (réversible)** — Sans `outils`, l'agent est en LECTURE : le moindre privilège par défaut.
- **A2 (réversible)** — Clés et noms d'outils Claude Code acceptés (parité) en plus des clés françaises.
- **A3 (réversible)** — Un agent invalide reste visible dans l'écran Gouvernance (avec son erreur) mais ne
  sera ni annoncé ni délégable (SF-182-02).
- **A4 (réversible)** — L'annonce au modèle (catalogue plafonné dans le message) est portée par SF-182-02, avec
  le paramètre `agent` qu'elle annonce : annoncer un agent qu'on ne peut pas encore déléguer serait faux.
