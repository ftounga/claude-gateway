# Mini-spec — F-38 / SF-38-27 — Le runner élit son interpréteur

## Identifiant

`F-38 / SF-38-27`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-38-27-election-interpreteur`

---

## Objectif

> Que le runner **choisisse** l'interpréteur sous lequel il exécutera les commandes — bash POSIX,
> puis PowerShell, puis `cmd.exe` — et le **déclare** à la gateway, pour que la consigne système
> cesse de dicter au modèle une syntaxe que la machine ne comprend pas.

---

## Déclencheur

Deux affirmations du code se contredisent aujourd'hui.

`BashTool.shellCommand` (ligne 152) :

```java
return os.contains("win")
        ? List.of("cmd.exe", "/c", command)
        : List.of("/bin/sh", "-c", command);
```

`AtelierChatService.buildSystemPrompt` (ligne 1344), en cible `RUNNER` :

> « Explore avec bash (`ls`, `find`, `grep -n`) — c'est le bon outil pour lister, chercher et
> vérifier. »

Et en cible `RUNNER`, `list_files` et `search_files` **ne sont pas déclarés** (SF-39-05) : `bash`
est le **seul** moyen d'explorer. Sur un poste Windows, le modèle reçoit donc l'ordre d'employer
`ls`, `find` et `grep -n` dans un `cmd.exe` qui n'en connaît aucun. Chaque exploration revient en
`'ls' n'est pas reconnu en tant que commande interne ou externe`, le modèle réessaie, et le tour se
consume en erreurs.

Or ces postes ont presque toujours un bash POSIX : **Git Bash** est installé avec Git pour Windows,
que tout poste de développement possède. Le runner ne le cherche simplement jamais.

---

## Comportement attendu

### Cas nominal — l'élection au démarrage

Au démarrage, avant toute connexion, le runner élit un interpréteur dans cet ordre :

| Rang | Interpréteur | Où il est cherché |
|------|--------------|-------------------|
| 1 | **bash POSIX** | `/bin/sh` (systèmes Unix) ; sous Windows : Git Bash aux emplacements d'installation connus, puis un `bash.exe` du `PATH` |
| 2 | **PowerShell** | `pwsh.exe`, puis `powershell.exe` du `PATH`, puis le chemin système `System32\WindowsPowerShell\v1.0` |
| 3 | **`cmd.exe`** | `%ComSpec%`, sinon `cmd.exe` |

L'élection est **annoncée sur la console** au même titre que le compte et les exclusions :

```
Interpréteur : bash POSIX — C:\Program Files\Git\bin\bash.exe
```

Elle est **déclarée à la gateway** dans la trame `ready`, champ `shell` : `posix`, `powershell` ou
`cmd`. La gateway l'enregistre sur le projet et **la consigne système suit** :

| `shell` déclaré | Ce que la consigne dicte |
|-----------------|--------------------------|
| `posix` (ou rien) | `ls`, `find`, `grep -n` — le texte actuel, inchangé |
| `powershell` | `Get-ChildItem`, `Select-String`, et l'interdiction des alias Unix |
| `cmd` | `dir /s /b`, `findstr /s /n`, et le fait qu'il n'y a **ni `ls`, ni `grep`** |

### Le piège écarté : `bash.exe` de WSL

Windows 10+ pose un `bash.exe` dans `System32` : c'est le **lanceur WSL**, pas un shell du poste. Il
ouvre une distribution Linux dont le système de fichiers n'est pas celui du projet ; un `cwd`
`C:\Users\moi\projet` n'y existe pas. Tout `bash.exe` situé dans `System32` (ou `Sysnative`) est
donc **écarté** de l'élection.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun candidat trouvé (poste Windows sans bash ni PowerShell) | `cmd.exe` est élu — c'est le repli garanti, jamais d'échec de démarrage |
| Un candidat existe mais n'est pas exécutable | Il est ignoré, l'élection continue au rang suivant |
| L'élection lève (droits, système de fichiers exotique) | Repli sur le comportement d'avant (`/bin/sh` ou `cmd.exe` selon l'OS) ; le démarrage n'échoue jamais pour ça |
| Runner antérieur à cette version (pas de champ `shell`) | La gateway laisse la colonne nulle et la consigne garde son texte POSIX actuel |
| Champ `shell` inconnu ou farfelu dans la trame `ready` | Ignoré : rien n'est enregistré, la consigne reste au texte POSIX |

---

## Critères d'acceptation

- [ ] Sous Unix, l'interpréteur élu reste `/bin/sh -c` : **aucun changement de comportement**.
- [ ] Sous Windows, un Git Bash présent est élu avant PowerShell et avant `cmd.exe`.
- [ ] Sous Windows sans bash, PowerShell est élu avant `cmd.exe`.
- [ ] Sous Windows sans bash ni PowerShell, `cmd.exe` est élu.
- [ ] Un `bash.exe` situé dans `System32` n'est jamais élu (lanceur WSL).
- [ ] La trame `ready` porte `shell` valant `posix`, `powershell` ou `cmd`.
- [ ] La gateway enregistre la valeur déclarée sur le projet, et **ignore** toute valeur hors liste.
- [ ] La consigne système en cible `RUNNER` dicte `ls`/`find`/`grep -n` quand `shell` vaut `posix`
      ou n'est pas renseigné, et la syntaxe correspondante sinon.
- [ ] La console annonce l'interpréteur élu au démarrage.
- [ ] Aucun chemin absolu de la machine ne remonte à la gateway : seul le **genre** est déclaré.

---

## Périmètre

### Hors scope

- **Traduire les commandes** du modèle d'une syntaxe vers une autre : réécrire `ls` en `dir` serait
  réimplémenter un shell dans la gateway, et personne ne saurait plus ce qui a réellement tourné.
- **Installer** un interpréteur, ou proposer de le faire.
- Un drapeau `--shell` pour forcer l'élection : rien ne le demande aujourd'hui ; l'ajouter serait un
  réglage de plus à documenter et à tester.
- **Afficher l'interpréteur dans l'écran** du projet : il change ce que le modèle écrit, pas ce que
  l'utilisateur décide. Il est annoncé là où il se choisit — la console du runner. Aucun écran
  Angular n'est donc touché par cette subfeature.
- **Redéclarer `list_files`/`search_files`** quand l'interpréteur n'est pas POSIX : PowerShell et
  `cmd.exe` savent tous deux lister et chercher. Rétablir deux définitions d'outils que SF-39-05 a
  retirées coûterait le préfixe caché à chaque itération, pour une capacité déjà disponible.
- Le contrat de protocole reste en version `1` : `shell` est un champ **facultatif** de plus dans
  une trame existante, et les trames inattendues sont déjà ignorées en silence (contrat §0).

---

## Technique

| Classe | Changement |
|--------|-----------|
| `runner/ShellElection` | **Nouvelle** — l'élection, sa ligne de commande, son nom déclaré |
| `runner/BashTool` | Utilise l'interpréteur élu au lieu de décider lui-même |
| `runner/ToolStack` | Élit au montage, annonce sur la console, passe l'élu au `BashTool` |
| `runner/ToolDispatcher` | Champ `shell` dans la trame `ready` |
| `backend/atelier/RunnerShell` | **Nouvelle** — la liste blanche et les trois consignes d'exploration |
| `backend/atelier/RunnerShellRecorder` | **Nouvelle** — port étroit : le canal runner n'a pas à voir toute la surface de `WorkspaceService` |
| `backend/runner/channel/RunnerCallDispatcher` | Lit `shell` dans `ready`, le fait enregistrer |
| `backend/atelier/WorkspaceService` | `recordRunnerShell` — liste blanche, écriture seulement si la valeur change |
| `backend/atelier/Workspace` | Colonne `runner_shell` |
| `backend/atelier/AtelierChatService` | La consigne système suit l'interpréteur déclaré |

### Migration Liquibase

- [x] **Applicable** — `063-workspaces-runner-shell.xml` : `workspaces.runner_shell` `varchar(16)`
      nullable, sans défaut (les projets existants n'ont rien déclaré). Rollback : `dropColumn`.

### Contraintes de validation

| Champ | Contrainte |
|-------|-----------|
| `shell` (trame `ready`) | Chaîne, liste blanche stricte `posix` \| `powershell` \| `cmd` ; toute autre valeur est ignorée |
| `workspaces.runner_shell` | `varchar(16)`, nullable, écrit uniquement depuis la liste blanche |

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | L'identité de la trame vient de la session runner, comme avant |
| Contexte tenant | **Oui** | L'écriture est faite pour le `workspaceId` **de la session runner**, jamais d'un champ du message : `RunnerCallDispatcher.onReady` (identité de session), `WorkspaceService.recordRunnerShell` (recherche par id de projet, comme `recordRunnerDeclaration`). Aucun accès utilisateur n'est ajouté : la consigne système lit le `Workspace` déjà chargé et déjà filtré par `user_id`. |
| Plans / limites | Non | — |
| Navigation / routing | Non | Aucun écran, aucune route |
| **Exécution de commandes** | **Oui** | L'interpréteur change la ligne de commande réellement lancée. Composants à vérifier : `BashTool` (exécution, annulation, délai, plafond de sortie), les deux transports qui montent la pile (`RunnerConnection`, `PollingConnection`) — tous deux passent par `ToolStack`, seul point de montage (garantie SF-38-09). Les gardes en amont sont **inchangées** : confinement `PathGuard`, exclusions, porte de confirmation, journal d'audit, coupe-circuit. |

---

## Plan de test

### Tests unitaires — runner

- [ ] Sur un système Unix, l'élection rend `/bin/sh -c` (non-régression).
- [ ] Sous Windows, un Git Bash présent est élu (rang 1), et la ligne est `bash.exe -c <commande>`.
- [ ] Sous Windows sans bash, `pwsh`/`powershell` est élu, avec `-NoProfile -NonInteractive -Command`.
- [ ] Sous Windows sans rien, `cmd.exe /c` est élu.
- [ ] Un `bash.exe` de `System32` n'est pas élu, même s'il est le seul candidat du `PATH`.
- [ ] Un candidat non exécutable est ignoré.
- [ ] Le nom déclaré vaut `posix`, `powershell` ou `cmd`, jamais autre chose.
- [ ] `BashTool` exécute réellement une commande avec l'interpréteur qu'on lui donne.
- [ ] La trame `ready` porte le champ `shell`.

### Tests unitaires — backend

- [ ] `onReady` avec `shell: "powershell"` fait enregistrer `powershell`.
- [ ] `onReady` sans `shell` n'enregistre rien.
- [ ] `onReady` avec une valeur hors liste blanche n'enregistre rien.
- [ ] `recordRunnerShell` n'écrit pas si la valeur est déjà celle du projet.
- [ ] La consigne système dicte `ls`/`find`/`grep -n` pour `posix` et pour un projet sans déclaration.
- [ ] La consigne système dicte `Get-ChildItem`/`Select-String` pour `powershell`.
- [ ] La consigne système dicte `dir`/`findstr` pour `cmd`.
- [ ] La consigne d'un projet en cible `SANDBOX` est **inchangée** quelle que soit la colonne.

### Isolation workspace

- [ ] L'enregistrement porte sur le `workspaceId` de la session runner authentifiée, jamais sur un
      identifiant lu dans la trame.

---

## Notes et décisions

**D1 — Élire, pas traduire.** La tentation est de réécrire `ls` en `dir` côté gateway. Ce serait
réimplémenter un shell, et surtout : personne ne saurait plus ce qui a réellement tourné sur la
machine — le journal d'audit afficherait une commande que l'utilisateur n'a pas autorisée. On change
l'interpréteur, jamais la commande.

**D2 — bash POSIX en premier, parce que c'est ce que le modèle sait faire.** L'usage réel mesuré est
à 95 % de `bash`, et tout l'outillage (`ls`, `find`, `grep -n`) suppose POSIX. Élire Git Bash quand
il est là, c'est aligner la machine sur la consigne plutôt que l'inverse.

**D3 — Écarter le `bash.exe` de WSL.** C'est le piège de cette subfeature : `System32\bash.exe`
existe sur presque tout Windows 10+, il est dans le `PATH`, et il *répond* à `--version`. Mais il
ouvre une distribution Linux dont le système de fichiers n'est pas celui du projet : le `cwd`
confiné par le `PathGuard` n'y existe pas, et chaque commande échouerait sur un chemin introuvable.
Un candidat plausible qui casse tout vaut moins que pas de candidat du tout.

**D4 — Déclarer dans `ready`, pas à l'appairage.** L'appairage n'a lieu **qu'une fois** : ensuite le
runner réutilise son jeton stocké et ne renvoie plus jamais le corps d'appairage. Une déclaration
posée là serait figée à vie — un Git Bash installé le lendemain ne serait jamais vu. La trame `ready`
part à **chaque connexion** : la déclaration reste vraie. (C'est un écart assumé avec SF-38-18, qui
déclare l'élévation à l'appairage ; l'élévation, elle, ne change pas d'un lancement à l'autre.)

**D5 — Persister la déclaration plutôt que la garder en mémoire.** Les capacités du `ready` vivent
dans la mémoire du pod qui porte la socket. La consigne système, elle, est construite par le pod qui
sert le message — qui n'est pas forcément le même (HPA `min 1 / max 4`, SF-38-12). Une colonne sur
`workspaces` est le seul endroit que les deux voient. L'écriture n'a lieu qu'à la connexion, et
seulement si la valeur change.

**D6 — Liste blanche stricte.** Le champ vient d'un client. Il n'est pas affiché tel quel, il n'est
pas concaténé dans une commande, et il ne sert qu'à choisir entre trois textes écrits en dur. La
liste blanche fait que même une valeur hostile ne produit rien d'autre que le texte par défaut.

**D8 — PowerShell reçoit une commande encodée** (décision prise au développement, hors mini-spec
initiale). Sous Windows, `ProcessBuilder` recompose une ligne de commande unique en citant les
arguments, et cette citation ne survit pas aux guillemets internes : `Select-String -Pattern "class
Foo"` arriverait déformé. `-EncodedCommand` (base64 d'UTF-16LE) supprime la question — c'est ce que
la documentation de PowerShell recommande pour une commande complexe, et SF-38-23 a déjà montré ce
que coûte un shell qui avale une partie de ce qu'on lui donne. Le script encodé se termine par
`exit $LASTEXITCODE` : sans lui, `powershell.exe` rendrait `0` même après une commande native en
échec, alors que le contrat du runner (§2.3) promet le code réel. Aucune traduction n'a lieu : la
commande écrite par le modèle est transmise **mot pour mot**, seul son emballage change.

**D7 — `cmd.exe` reste le repli, pas une erreur.** Un poste sans bash ni PowerShell est rare, mais
refuser de démarrer y serait absurde : les outils fichiers, eux, marchent partout. Le runner
fonctionne, et la consigne dit au modèle la vérité sur ce qu'il a sous la main.
