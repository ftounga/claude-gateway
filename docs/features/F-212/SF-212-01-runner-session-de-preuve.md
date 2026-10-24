# Mini-spec — F-212 / SF-212-01 Runner : la session de preuve `proof_run`

## Identifiant
`F-212 / SF-212-01` — Feature parente : F-212 « Les captures de preuve » — Statut : `in-review` — 2026-10-24
Branche : `feat/SF-212-01-runner-capture-preuve`

## Objectif
Le runner exécute 1 à 8 commandes **à la suite dans un même shell** et rend, par commande, la sortie brute (stdout/stderr distincts), le code de sortie, la durée, le dossier courant et l'heure — plus ce qu'il faut pour imiter le vrai terminal du poste (amendement PO du 2026-10-24).

## Comportement attendu
### Cas nominal
- Outil `proof_run` (`{"commands":[…], "cwd"?}`), capacité `proof` annoncée dans la trame `ready` dès que `bash` est autorisé.
- Un script unique, un seul processus : chaque commande est encadrée de **bornes** (`@@CGP-<jeton>:B:n` / `:E:n:<code>:<cwd>`) imprimées sur les deux flux ; jeton aléatoire neuf à chaque appel (une sortie ne peut pas l'imiter). `cd`, variables et fonctions sont conservés d'une commande à l'autre.
- Shell de la session : sur Linux/macOS, le **`$SHELL` de l'utilisateur** (`-c`, non login) s'il parle POSIX (bash, zsh, sh, dash, ksh), sinon le shell élu (ex. fish) ; sur Windows le shell élu (PowerShell : dot-source + `Out-String -Stream` ; cmd : fichier `.cmd` temporaire).
- Relevé JSON : `os`, `user`, `host` (nom court), `home`, `interactiveShell`, `execShell`, `shell`, `cwd` de départ (absolu) ; par commande `index, command, status (done|interrupted|not_run), stdout, stderr, exitCode, durationMs, startedAt, cwd, truncated`.
- Bornes : 64 Kio par commande (par flux : 24 Ki caractères de tête + 8 Ki de fin, mention « … [N caractères omis] … »).
- Un jeton du sémaphore bash pour toute la session (compte comme une commande de premier plan).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `--no-bash` | `unsupported_tool`, capacité `proof` non annoncée |
| 0 ou > 8 commandes, commande vide, > 8192 caractères, NUL | `invalid_input` |
| `cwd` absent / pas un dossier | `not_found` / `not_a_file` |
| poste occupé (N commandes en cours) | `denied` + message BUSY du contrat F-188 |
| une commande fait `exit 4` | elle est `interrupted` (code 4), les suivantes `not_run` |
| annulation / délai | `cancelled` / `timeout` (aiguilleur) ; processus tué |

## Critères d'acceptation
- [x] cwd et variables partagés entre commandes (test réel sous sh).
- [x] stdout/stderr séparés, code et durée par commande ; sortie sans fin de ligne restituée exactement.
- [x] une sortie qui imite une borne sans le jeton reste du contenu.
- [x] borne tête + fin et mention de troncature.
- [x] `exit` : interrupted / not_run.
- [x] capacité `proof` annoncée seulement si bash est autorisé ; routage `proof_run`.
- [x] `$SHELL` POSIX retenu, fish écarté, nom d'hôte court, système détecté.
- [x] scripts PowerShell et cmd porteurs des bornes (tests de texte ; exécution réelle à vérifier sur poste Windows).

## Plan de test
Unitaires/intégration runner : `ProofRunTest` (11 cas, shell réel), tests de capacités mis à jour (`ToolRouterTest`, `ProjectScopesTest`, `TeamsRoutingTest`, `ToolDispatcherTest`). Suite runner complète. Isolation utilisateur : sans objet côté runner (le poste est l'unité ; l'isolation `user_id` est à la gateway, SF-212-03).

## Préoccupations transversales
- Auth / Principal : non. Contexte tenant : non (runner). Plans/limites : non. Navigation : non.
- **Sécurité du poste** : aucune inspection ajoutée ni retirée — la porte et l'invite d'autorisation restent à la gateway, appliquées à CHAQUE commande (SF-212-03). Composants impactés : `BashTool` (nouvelle méthode, même sémaphore), `ToolRouter` (routage + capacité).

## Impacts
Runner : `ProofRun` (nouveau), `ProofHost` (nouveau), `BashTool.proofRun`, `ToolRouter`, `release-notes.txt`. Aucune table, aucun endpoint.

## Coût (exigence PO)
Le runner ne rend que du texte ; la borne au modèle (≈ 2 000 caractères par commande, ≈ 6 000 au total) est appliquée par la gateway (SF-212-03).

## Hors périmètre
Image (SF-212-02), outil agent / porte / stockage (SF-212-03), affichage (SF-212-04). Invite personnalisée par poste (reste à faire, voir cadrage).
