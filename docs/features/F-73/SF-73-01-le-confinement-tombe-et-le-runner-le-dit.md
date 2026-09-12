# Mini-spec — F-73 / SF-73-01 — Le confinement tombe, et le runner le dit

---

## Identifiant

`F-73 / SF-73-01`

## Feature parente

`F-73` — Le runner n'est plus confiné

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-73-01-confinement-retire-runner`

---

## Objectif

> Retirer du runner le confinement des chemins et les exclusions de secrets, **et remplacer la
> phrase fausse qu'ils portaient** — dans le code comme sur la console de démarrage — par ce qui est
> vrai : ce runner exécute sur cette machine, avec les droits du compte qui l'a lancé, sans
> restriction de dossier.

---

## Déclencheur

Décision du PO du 2026-09-12 (`docs/PRODUCT_SPEC.md`, F-73 ; cadrage `F-73-cadrage.md`, D1/D2/D5/D6).
Le confinement **n'existait déjà pas** pour `bash` : `BashTool.resolveWorkingDirectory` validait le
`cwd`, jamais la commande. Le commentaire qui affirmait le contraire est la première chose à
corriger.

---

## Comportement attendu

### Cas nominal

**(1) Un chemin adressé n'est plus confiné.** `read_file` et `write_file` acceptent :

| Forme demandée | Résolution |
|---|---|
| `src/App.java` (relative) | résolue **sous le dossier du projet**, comme avant |
| `../autre-projet/pom.xml` | résolue, et **acceptée** |
| `/etc/hosts`, `C:\Windows\win.ini` | chemin **absolu**, accepté tel quel |
| `~/.ssh/config` | `~` étendu au dossier du compte (`user.home`), accepté |

Le code d'erreur `path_outside_root` **n'est plus jamais rendu par les outils fichiers**. Les bornes
qui restent sont celles de **taille et de forme** : chemin vide, plus de 4096 caractères, octet nul,
fichier de plus de 8 Mio (`too_large`), contenu écrit de plus de 512 Kio.

**(2) Les exclusions de secrets disparaissent.** `ExclusionRules.DEFAULT_DENY` (`.env`, `*.pem`,
`id_rsa*`, `.aws/`, `.kube/config`, `.ssh/`) est **supprimée**, avec toute la mécanique de règle
« non désactivable » qui la portait. Un `read_file` sur `.env` rend le contenu du fichier.

**(3) Ce qui reste filtré, et pourquoi.** `.runnerignore` (à défaut `.gitignore`) et le **bruit de
construction** (`DEFAULT_NOISE` : `node_modules/`, `target/`, `dist/`…) continuent d'élaguer le
**balayage** de `list_files` et `search_files` — question de lisibilité, pas de secret (SF-38-21).
Ils ne s'appliquent **pas** à un chemin adressé : `read_file` sur `node_modules/x/package.json`
réussit. Toutes les règles restent **négociables** par une négation (`!node_modules/`).

**(4) La classe dit ce qu'elle fait.** `PathGuard` devient **`PathResolver`** : elle résout et
normalise, elle ne garde plus. `Resolved.relative()` devient `Resolved.display()` — la forme du
chemin telle que l'appelant l'a demandée, celle qui apparaît dans les messages.

**(5) Le commentaire mensonger est corrigé.** Dans `BashTool`, la phrase « *Une commande ne
s'exécute jamais hors de la racine exposée* » est remplacée par ce qui est vrai : le `cwd` est le
dossier de départ ; **la commande, elle, n'est pas inspectée et peut aller où le compte peut aller**.
Même traitement pour les en-têtes de `FileTools`, `ToolStack`, `ToolScopes`, `ProjectScopes`,
`ShellElection` et `PairingClient`, qui répétaient la promesse.

**(6) Le runner l'annonce au démarrage.** `StartupDisclosure` (F-57 / SF-57-01) rend désormais
**cinq** lignes au lieu de quatre. La première est réécrite, une ligne `Portée` est insérée avant
`Compte` :

```
Ce runner : exécute sur cette machine les commandes que vous autorisez depuis la Forge,
            avec les droits du compte ci-dessous. Rien ne part sans votre geste.
Portée    : aucune restriction de dossier. Ce runner lit et écrit partout où ce compte le
            peut — y compris .env, clés SSH et .aws/ — et ce qu'il lit part chez le
            fournisseur dans le contexte du tour.
Compte    : …
Route     : …
Rappel    : …
```

Ton **factuel** (D6) : aucun « attention », aucun « danger ». C'est sa machine, il a lancé ce
programme lui-même.

**(7) La console de montage cesse de promettre.** `ToolStack` remplace « *Chaque tour est confiné au
dossier du projet qu'il vise* » par « *Le dossier du projet est le point de départ de chaque tour ;
il ne borne pas ce qu'une commande peut atteindre.* », et la ligne « Exclusions » ne cite plus de
liste non désactivable — elle annonce le filtre de **listage**. `RunnerMain` fait de même sur la
ligne `Poste`.

**(8) Ce qui ne bouge pas.** `--no-bash` (SF-38-19), le sémaphore à un jeton, les plafonds de
diffusion, le délai, l'annulation, le journal d'audit côté gateway et le coupe-circuit : **inchangés**.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `path` vide, absent, ou non textuel | Refus de forme | `invalid_input` |
| `path` de plus de 4096 caractères, ou contenant un octet nul | Refus de forme | `invalid_input` |
| `read_file` sur un chemin absolu **inexistant** | Refus, message citant le chemin demandé | `not_found` |
| `read_file` sur un dossier | Refus | `is_directory` |
| `read_file` sur un fichier que le compte **ne peut pas lire** (droits) | Refus de l'OS, jamais une promesse de confinement | `io_error` |
| `read_file` sur un fichier de plus de 8 Mio | Refus | `too_large` |
| `write_file` dont le parent n'est pas créable (droits) | Refus | `io_error` |
| `bash` avec un `cwd` inexistant | Refus | `not_found` |
| `bash` avec un `cwd` qui n'est pas un dossier | Refus | `not_a_file` |
| Chemin de **projet** (champ `project` de la trame) malformé ou inexistant | Refus — c'est la validité du dossier de départ, pas un confinement | `invalid_input` / `not_found` |

---

## Critères d'acceptation

1. `read_file` sur `../hors-projet.txt` **réussit** et rend le contenu du fichier.
2. `read_file` sur un chemin **absolu** hors racine réussit ; `write_file` sur un chemin absolu hors
   racine écrit réellement le fichier.
3. `read_file` sur `.env` **réussit** : plus aucun code `excluded` n'est rendu par les outils
   fichiers, quel que soit le chemin demandé.
4. `read_file` sur `~/…` étend `~` au dossier du compte et lit le fichier.
5. `list_files` **n'énumère pas** `node_modules/` ni ce qu'exclut `.runnerignore` ; `read_file` sur
   un fichier situé sous ces dossiers **réussit** malgré tout (A2).
6. Une négation `!node_modules/` dans `.runnerignore` **réinclut** le dossier dans le listage, et
   une négation `!.env` n'a plus rien à réactiver (la règle par défaut n'existe plus).
7. `bash` avec un `cwd` relatif démarre dans le dossier du projet ; une commande qui remonte
   (`cd .. && pwd`) **fonctionne** — et le test le vérifie explicitement, pour que la suite dise la
   vérité du produit.
8. `ExclusionRules.DEFAULT_DENY` **n'existe plus** dans le code (aucune référence, tests compris).
9. `StartupDisclosure.lines(...)` rend **5 lignes**, dont une commençant par `Portée` qui cite
   l'absence de restriction de dossier et le fait que ce qui est lu part chez le fournisseur.
10. Aucun texte de la console de démarrage ni aucun commentaire de `runner/src/main` n'affirme plus
    qu'un tour est « confiné », « borné » ou « ne sort pas » du dossier du projet.
11. `mvn -pl runner test` est **vert**.

---

## Périmètre

### Hors scope (explicite)

- Conteneuriser le runner (D9).
- Toute inspection de la ligne de commande (liste d'interdits) : inefficace par construction.
- Le journal d'audit et le coupe-circuit (côté gateway), inchangés (D7).
- Le défaut de la porte de confirmation : c'est **SF-73-02**.
- L'affichage dans l'application : c'est **SF-73-03**.

---

## Valeurs initiales

Aucune donnée créée. Bornes conservées : `MAX_PATH_LENGTH = 4096`, `MAX_READ_BYTES = 8 Mio`,
`MAX_CONTENT_BYTES = 512 Kio`, `LIST_MAX_ENTRIES = 20 000`, `MAX_COMMAND_CHARS = 8192`.

---

## Contraintes de validation

| Champ | Contrainte | Motif |
|---|---|---|
| `path` | non vide, ≤ 4096 caractères, sans octet nul | garde-fou d'entrée, pas de confinement |
| `path` | `~` étendu à `user.home` ; absolu accepté ; `..` accepté | D1 |
| `cwd` (bash) | mêmes règles, doit désigner un **dossier existant** | un `cwd` faux ferait échouer la commande sans le dire |
| `project` (trame) | relatif à la racine du poste, sans `..` | **validité** du dossier de départ (A4), pas une garantie |

---

## Technique

### Endpoint(s)

Aucun. Le contrat de trames (`tool_call` / `tool_result`) est inchangé : seuls les **codes** qui ne
peuvent plus se produire (`excluded`, `path_outside_root` sur les outils fichiers) disparaissent de
fait. La liste close du contrat §4 n'est pas modifiée.

### Tables impactées

Aucune.

### Migration Liquibase

Aucune.

### Composants Angular (si applicable)

Aucun.

### Fichiers du runner

| Fichier | Changement |
|---|---|
| `PathGuard.java` → `PathResolver.java` | renommé ; confinement et appel aux exclusions retirés ; `~` étendu ; absolu accepté |
| `ExclusionRules.java` | `DEFAULT_DENY` et la mécanique « non désactivable » supprimées ; ne sert plus qu'au balayage |
| `FileTools.java` | plus de refus `excluded` sur un chemin adressé ; en-tête corrigé |
| `BashTool.java` | **commentaire mensonger corrigé** ; `cwd` résolu sans confinement |
| `ProjectScopes.java` | l'en-tête dit « dossier de départ », non « confinement » ; charge les exclusions du projet |
| `ToolScopes.java`, `ToolStack.java`, `RunnerMain.java`, `ShellElection.java`, `PairingClient.java` | textes et commentaires corrigés |
| `StartupDisclosure.java` | 1ʳᵉ ligne réécrite + ligne `Portée` |

---

## Plan de test

### Tests unitaires

1. `PathResolverTest` (ex-`PathGuardTest`) : relatif résolu sous la racine ; `..` accepté ; absolu
   accepté ; `~` étendu ; chemin vide / trop long / avec octet nul refusés en `invalid_input`.
2. `ExclusionRulesTest` : plus de `DEFAULT_DENY` ; `!node_modules/` réinclut ; `.env` n'est plus
   exclu par défaut ; dernière règle qui correspond l'emporte.
3. `ExclusionEnforcementTest` : `list_files` élague le bruit et les règles utilisateur ;
   `read_file`/`write_file` sur les **mêmes** chemins réussissent.
4. `FileToolsTest` : lecture et écriture hors racine (chemin relatif remontant **et** absolu) ;
   lecture d'un `.env` ; erreurs `not_found`, `is_directory`, `too_large` conservées.
5. `BashToolTest` : `cd .. && pwd` sort du dossier du projet et **réussit** ; `cwd` inexistant →
   `not_found` ; `--no-bash` → `unsupported_tool`.
6. `StartupDisclosureTest` : 5 lignes, ordre stable, ligne `Portée` présente et non alarmiste
   (aucun « attention »/« danger »), compte et route inchangés.
7. Test de non-régression textuelle : aucun fichier de `runner/src/main` ne contient plus
   « confiné au dossier », « hors de la racine exposée » ni `DEFAULT_DENY`.

### Tests d'intégration

8. `ToolDispatcherTest` : un `tool_call` portant un `project` valide exécute dans ce dossier ; un
   `project` malformé termine l'appel en erreur **avant** toute exécution (inchangé).

### Isolation workspace

Sans objet côté runner : le processus est celui de l'utilisateur, sur sa machine, pour son seul
compte. L'isolation `user_id` reste entière côté gateway (appairage, audit) et n'est pas touchée.

---

## Dépendances

### Subfeatures bloquantes

Aucune.

### Questions ouvertes impactées

- **OQ-14** : rouverte puis retranchée par le PO le 2026-09-12 (voir SF-73-02 et **ADR-019**).
  Le point 3 d'**ADR-018** — « les exclusions de secrets restent non désactivables » — est
  **supersédé** par cette subfeature.

---

## Notes et décisions

- **Préoccupation transversale — aucune cochée.** Ni auth, ni contexte tenant, ni plans/limites, ni
  navigation : le changement est interne au processus runner. Les composants qui *lisent* le
  résultat des outils (`RunnerCallDispatcher`, `RunnerToolGateway`) ne changent pas de contrat — les
  codes d'erreur restent ceux de la liste close, deux d'entre eux cessent simplement de se produire.
- **Ce que l'on perd, dit franchement** : un runner compromis côté réseau pouvait, jusqu'ici, lire
  n'importe quoi via `bash` mais pas via `read_file`. Il le peut désormais par les deux chemins. La
  différence est nulle en pratique (`bash` suffisait), et le PO a tranché en connaissance de cause :
  ce qui protège est la **porte** (SF-73-02) et ce qui est **dit** (SF-73-03), plus une garde que
  seuls les outils fichiers respectaient.
