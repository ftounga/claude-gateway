# Mini-spec — F-48 / SF-48-02 — Le confinement par sous-dossier, côté runner

## Identifiant

`F-48 / SF-48-02`

## Feature parente

`F-48` — Le poste comme unité

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-48-02-confinement-sous-dossier`

---

## Objectif

Le runner reçoit la **racine du poste** au démarrage et le **sous-dossier du projet** à chaque appel,
et `PathGuard` referme le confinement sur ce sous-dossier — la garantie reste **locale**.

---

## Pourquoi cette subfeature existe

SF-48-01 a fait du poste l'unité : le runner est désormais lancé à la racine de la machine (`~/dev`),
et non plus dans le dossier d'un projet. Sans elle, un agent travaillant sur le projet A pourrait lire
— voire écrire — dans le projet B, puisque la racine confinée se serait élargie d'autant.

**Régime B du cadrage, décision n° 2, non réversible** : le refus de sortir doit rester la propriété
du **processus qui exécute**. Déplacer cette garantie dans la gateway ferait dépendre la promesse
centrale du mode runner d'un composant réseau — un défaut de la gateway ouvrirait toute la racine.

---

## Comportement attendu

### Cas nominal

1. `claude-runner --root ~/dev` (ou `--workspace`, conservé) : le runner canonicalise la racine du
   **poste**, l'annonce, et la déclare à l'appairage.
2. Chaque `tool_call` porte `project` — le chemin du projet relatif à cette racine, `""` pour la
   racine elle-même (SF-48-01).
3. Le runner résout ce projet **une fois par projet**, garde le `PathGuard` correspondant en cache,
   et exécute l'outil sous **ce** confinement : `read_file`, `write_file`, `list_files`,
   `search_files` et le `cwd` de `bash` ne sortent pas du sous-dossier.
4. Les exclusions (`.runnerignore`) sont chargées **dans le dossier du projet** : c'est un fichier de
   projet, et c'est là que l'utilisateur l'écrit. La liste par défaut non désactivable s'applique
   partout (D10, inchangée).

### Cas d'erreur

| Situation | Comportement attendu | Code rendu |
|---|---|---|
| `project` absolu, avec `..`, avec lettre de lecteur, ou octet nul | Refus, rien n'est exécuté | `path_outside_root` |
| `project` qui, après résolution des liens, sort de la racine du poste | Refus | `path_outside_root` |
| Dossier de projet inexistant, ou qui n'est pas un dossier | Refus, message citant le **chemin relatif** seulement | `not_found` |
| `project` trop long (> 4096) | Refus | `invalid_input` |
| Racine du poste illisible | Refus au démarrage (inchangé) | `io_error` |

Aucun message ne cite le chemin **absolu** de la machine : la garde de SF-38-04 est conservée.

---

## Critères d'acceptation

- [ ] `--root` est accepté et documenté ; `--workspace` reste accepté (une ligne de commande valide
      hier ne doit pas échouer demain) et désigne la même chose : la racine du **poste**.
- [ ] Le runner déclare cette racine à l'appairage, avec son système et ses droits.
- [ ] Un `tool_call` portant `project: "app"` exécute ses outils **confinés à** `<racine>/app`.
- [ ] Un chemin `../autre-projet/secret.txt` sous ce projet est refusé en `path_outside_root`,
      **même** si le fichier existe et est sous la racine du poste.
- [ ] Un lien symbolique du projet A vers le projet B est refusé de la même façon (canonicalisation).
- [ ] `project: ""` confine à la racine du poste — un poste peut n'héberger qu'un projet.
- [ ] Un `project` inconnu rend `not_found` sans jamais citer un chemin absolu.
- [ ] Le `cwd` de `bash` est borné au projet du même appel.
- [ ] Les deux transports (WebSocket, repli long-polling) montent **exactement** les mêmes gardes.
- [ ] `mvn -pl runner test` vert.

---

## Périmètre

### Hors scope

- Le réglage « ce projet peut lire cet autre projet » (monorepo, dépendance locale) : le cadrage le
  prévoit **par projet**, jamais par défaut — hors de cette subfeature.
- Les écrans (SF-48-03).

---

## Impacts

### Composants runner

`RunnerConfig` (racine du poste, `--root`), `ProjectScopes` (**nouveau** : résolution et cache des
confinements par projet), `ToolScopes` (**nouveau** : le port que l'aiguilleur consomme),
`ToolDispatcher` (lit `project`, résout la portée, refuse avant d'exécuter), `ToolStack`, `RunnerMain`,
`PairingClient` (déclare `os`).

### Tables / endpoints

Aucun. Le protocole ne gagne rien : le champ `project` existe déjà depuis SF-48-01.

### Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | inchangé (le jeton reste celui du poste) |
| Contexte tenant | non | inchangé |
| Plans / limites | non | inchangé |
| Navigation / routing | non | aucun frontend |

---

## Plan de test

### Unitaires

- `ProjectScopesTest` — résolution nominale, `""` = la racine, refus de `..`, d'un chemin absolu,
  d'une lettre de lecteur, d'un lien symbolique sortant, d'un dossier inexistant ; cache stable.
- `PathGuardTest` — inchangé (la garde elle-même ne change pas).
- `ToolDispatcherTest` — un `tool_call` avec `project` exécute sous le bon confinement ; un `project`
  invalide rend une trame terminale d'erreur et **n'exécute rien**.
- `RunnerConfigTest` — `--root` et `--workspace` désignent la même racine ; message d'usage à jour.

### Intégration (runner)

- Deux projets frères sous une même racine : le premier ne lit ni n'écrit dans le second.

### Isolation utilisateur

Sans objet côté runner (un runner sert un seul poste, d'un seul utilisateur) ; l'isolation
`user_id` est tenue par la gateway et testée en SF-48-01.
