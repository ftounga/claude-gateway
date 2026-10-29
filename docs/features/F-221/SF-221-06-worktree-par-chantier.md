# Mini-spec — [F-221 / SF-221-06] Un worktree par chantier parallèle

## Identifiant
SF-221-06

## Feature parente
F-221 — Deux chantiers côte à côte : un fil par chantier, le terminal en split

## Statut
in-progress

## Date de création
2026-10-29

## Branche Git
`feat/SF-221-06-worktree-par-chantier`

---

## Objectif
Deux chantiers qui travaillent en même temps sur le même poste ne se marchent pas dessus. Celui qui démarre alors qu'un autre fil du sujet tourne travaille dans **son propre worktree git**. Sans git, il est prévenu.

## Comportement attendu

### Cas nominal
- **Déclenchement** (cadrage A8). Au début d'un tour du **fil d'un chantier N**, sur un projet poste (cible runner), dans l'ordre :
  1. Le chantier a déjà son worktree (`subject_journey_chantiers.worktree_path`) : le tour y travaille. C'est **collant** jusqu'à la clôture du chantier.
  2. Sinon, si **un autre fil du sujet** a une boucle en cours sur ce pod (autre chantier, fil commun ou course autonome), la gateway crée le worktree :
     - `git rev-parse --is-inside-work-tree` ;
     - `git worktree add -b atelier/chantier-<id> <racine>/.atelier-chantiers/<id> HEAD` (la branche est réutilisée si elle existe déjà) ;
     - deux commandes simples, portables (POSIX et PowerShell), exécutées par la gateway sur le poste du projet ;
     - `<id>` = 8 premiers caractères du sujet + `-ch` + N ;
     - le dossier vit sous la racine du poste, **hors** `.atelier-worktrees` : le nettoyage de démarrage du runner (F-150 `reapAtStartup`) ne le touche jamais.

     Chemin et branche sont enregistrés sur le chantier.
  3. Sinon (premier fil actif) : copie principale, comme aujourd'hui.
- **Tout le tour s'exécute dans le worktree** : la cible runner du tour pointe le worktree (`RunnerTargets.of`, via le champ transitoire du `Workspace` du tour). Cela couvre `bash`, lecture, écriture, recherche, `capturer_preuves` et sous-tâches. La recherche servie par l'index de la carte est coupée sous un worktree (l'index reflète la copie principale). Les **permissions ne changent pas** : pas d'acceptation automatique des éditions, à la différence de `task`.
- **Ce que l'agent sait** (dans le **message**, jamais au système) : « [Isolé] Ce chantier travaille dans son worktree git `<chemin>` (branche `<branche>`), parti du dernier commit : la copie principale et l'autre chantier n'y voient rien. Committe sur cette branche ; en fin de chantier, propose la fusion — ne fusionne rien sans accord. »
- **Repli sans git** : projet non git, git absent ou création refusée. Le tour part dans la copie principale. Le message dit : « [Sans isolation] Un autre chantier travaille en même temps dans ce dossier : ne modifie pas les mêmes fichiers ; si c'est inévitable, dis-le à l'utilisateur. » Rien n'est écrit hors de la racine du poste.

### Cas d'erreur
| Situation | Comportement |
|-----------|--------------|
| Poste hors ligne au moment de créer | pas de worktree, repli « sans isolation » ; la porte du runner (F-161) s'applique au tour comme d'habitude |
| Branche `atelier/chantier-<id>` déjà présente | elle est réutilisée (`git worktree add <chemin> <branche>`) |
| Projet sandbox (pas de poste) | sans objet |
| Fil commun | sans objet (seuls les chantiers s'isolent) |

## Critères d'acceptation
- [ ] Un tour du fil 8 qui démarre pendant que le fil 7 tourne :
  - crée le worktree (deux commandes git) ;
  - l'enregistre sur le chantier 8 ;
  - joint « [Isolé] » au message ;
  - exécute ses outils avec la cible `.atelier-chantiers/<id>`.
- [ ] Le tour suivant du fil 8, même seul, reste dans le worktree (collant) sans recréer.
- [ ] Le fil 8 seul (aucun autre fil actif) et sans worktree : copie principale, aucune commande git.
- [ ] Projet non git : aucun worktree, « [Sans isolation] » au message.
- [ ] La consigne système est identique avec et sans worktree.

## Périmètre

### Hors scope (explicite)
- La fusion automatique de la branche (toujours un geste explicite).
- Le démontage automatique du worktree à la clôture du chantier : il reste sur disque avec sa branche (limite connue, à traiter si la mesure le demande).
- Une copie de travail de repli pour les projets non git (le PO a écarté la copie pour `task` ; même ligne ici).
- L'explorateur de fichiers de l'écran : il montre la copie principale.

## Valeurs initiales
- `worktree_path` / `worktree_branch` : `NULL` (copie principale).

## Contraintes de validation
- `<id>` limité à `[A-Za-z0-9-]`. Le chemin est toujours sous la racine du poste.

## Technique

### Endpoint(s)
- Aucun.

### Tables impactées
- `subject_journey_chantiers` : + `worktree_path varchar(255)`, `worktree_branch varchar(160)`.

### Migration Liquibase
- [x] Oui — `179-chantier-worktree.xml` (numéro revérifié au merge). Rollback : colonnes retirées.

### Composants
- `SubjectJourneyChantier` (2 champs).
- `SubjectJourneyService` : `chantierWorktree` et `recordChantierWorktree`.
- `Workspace` (transitoires `turnWorktreePath`, `turnWorktreeBranch`, `turnUnisolated`).
- `RunnerTargets.of`.
- `AtelierChatService` : isolement du fil, note du message, index coupé.

## Plan de test

### Tests unitaires
- [ ] `RunnerTargets.of` : le worktree du tour remplace le chemin du projet.
- [ ] `LaneWorktree` (pur) :
  - identifiant ;
  - chemin relatif depuis le projet (profondeur 0, 1 ou 2) ;
  - commandes.
- [ ] `AtelierChatService` :
  - fil 8 pendant le fil 7 → création + note + cible ;
  - collant ;
  - seul → rien ;
  - non git → « [Sans isolation] » ;
  - système identique.

### Tests d'intégration
- [ ] Migration jouée (suite complète) ; `recordChantierWorktree` puis `chantierWorktree` sur base H2 (`SubjectJourneyChantiersIntegrationTest`).

### Isolation utilisateur
- [x] Écriture sur la ligne du chantier filtrée `user_id` + `workspace_id`.

## Préoccupations transversales
- **Contexte tenant** : `RunnerTargets.of` est lu partout. Il ne change que pour l'instance de `Workspace` d'un tour de fil isolé, champ transitoire jamais persisté. Composants vérifiés :
  - les sites de `AtelierChatService` (exécution runner, preuves, `task`) ;
  - le démarrage ;
  - les commandes d'amorçage.

  Ailleurs, l'entité est chargée sans fil : chemin inchangé.

## Dépendances
- SF-221-01, SF-221-02 (mergées).

## Notes et décisions
- **Pas de nouvelle opération runner** : la création passe par `bash` (deux commandes git), donc les runners déjà installés suffisent ; aucune mise à jour du poste n'est exigée. Le dossier `.atelier-chantiers` échappe au nettoyage `worktree_reap` de F-150, qui ne vise que `.atelier-worktrees`.
