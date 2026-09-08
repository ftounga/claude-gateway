# Mini-spec — F-46 / SF-46-01 — Le runner se souvient de sa passerelle et de sa racine

## Identifiant

`F-46 / SF-46-01`

## Feature parente

`F-46` — Reprendre le runner sans le réinstaller

## Statut

`done` — PR #302, mergée le 2026-09-08

## Date de création

2026-09-08

## Branche Git

`feat/SF-46-01-reprise-sans-argument`

---

## Objectif

> Qu'un runner **déjà appairé** redémarre en tapant `java -jar claude-runner.jar`, sans un seul
> argument — la passerelle et la racine du projet étant relues d'une mémoire écrite à l'appairage.

---

## Déclencheur

`RunnerConfig.resolve` exige `--gateway` **et** `--workspace`. Le jeton stocké
(`<workspace>/.claude-runner/token.json`) ne porte ni l'un ni l'autre : la seule information qui
survit au redémarrage du poste est **le secret**, pas le contexte qui permet de s'en servir.

Résultat observé chez les deux premiers clients : pour rouvrir une session sur une machine appairée
la veille, l'utilisateur rouvre l'application, régénère parfois un code dont il n'a pas besoin, et
recopie une commande de trois arguments dont un chemin absolu.

---

## Comportement attendu

### Cas nominal — l'appairage écrit la mémoire

À l'**appairage réussi** (et seulement là), le runner écrit, **à côté du jeton**, un fichier de
reprise `session.json` :

```json
{
  "gateway": "https://portal.ng-itconsulting.com/api",
  "workspaceRoot": "/home/moi/projet",
  "updatedAt": "2026-09-08T10:12:00Z"
}
```

Il **ne contient aucun secret** — ni jeton, ni code, ni identifiant d'utilisateur. Le même contenu
est déposé en `~/.claude-runner/session.json` : c'est ce qui rend la reprise possible **hors** du
dossier du projet (double-clic du lanceur, SF-46-02), sans jamais dupliquer le jeton.

### Cas nominal — le redémarrage sans argument

`java -jar claude-runner.jar`, sans argument, depuis le projet :

1. **Localiser la mémoire** — `<courant>/.claude-runner/session.json`, puis les **répertoires
   parents** (le runner se lance souvent depuis un sous-dossier), puis
   `~/.claude-runner/session.json`.
2. **En déduire** la passerelle et la racine. La racine mémorisée l'emporte ; si elle n'existe plus
   et que la mémoire est celle d'un projet (`<X>/.claude-runner/session.json`), `X` prend sa place —
   un projet déplacé avec son dossier `.claude-runner` reste reprenable.
3. **Poursuivre le démarrage habituel** : contrôle de vol réseau (SF-38-25), jeton stocké relu
   (SF-38-03), connexion.

La console dit d'où vient la configuration :

```
Reprise   : configuration mémorisée (/home/moi/projet/.claude-runner/session.json)
Gateway   : https://portal.ng-itconsulting.com/api
Workspace : /home/moi/projet
```

### Précédence

`argument CLI > variable d'environnement > mémoire de reprise`. La mémoire est le **dernier**
recours : une commande explicite n'est jamais réécrite par un fichier. Un `--workspace` fourni
l'emporte même si une mémoire existe ; un `--gateway` fourni aussi, et les deux peuvent se combiner
avec la mémoire pour l'autre champ.

### Cas d'erreur

| Situation | Comportement attendu | Code de sortie |
|-----------|---------------------|----------------|
| Aucun argument **et** aucune mémoire trouvée | Refus nommant les deux gestes possibles : relancer depuis le dossier du projet, ou reprendre la commande d'installation dans l'application | `2` |
| Mémoire trouvée mais **jeton absent** (jamais appairé, ou jeton effacé) | Refus explicite : « une machine est mémorisée, mais aucun jeton — relancez avec `--code` » | `2` |
| Mémoire trouvée mais **jeton expiré** | Refus **nommant l'expiration et sa date**, et demandant un nouveau code. Aucun réappairage silencieux | `2` |
| Mémoire trouvée mais **racine disparue** (et non déductible) | Refus nommant le chemin mémorisé et invitant à relancer avec `--workspace` | `2` |
| Mémoire **illisible ou corrompue** | Traitée comme absente (pas de plantage), et le refus est celui du premier cas | `2` |
| Mémoire écrite impossible (disque plein, dossier en lecture seule) | L'appairage **réussit quand même** ; un `WARN` dit que la reprise ne sera pas mémorisée | — |

---

## Critères d'acceptation

1. Après un appairage réussi, `<workspace>/.claude-runner/session.json` **et**
   `~/.claude-runner/session.json` existent et portent la passerelle et la racine.
2. Aucun de ces deux fichiers ne contient le jeton, le code d'appairage, ni aucune autre valeur
   secrète.
3. `java -jar claude-runner.jar` **sans argument**, depuis un dossier dont un ancêtre porte la
   mémoire, démarre avec la même passerelle et la même racine que le lancement d'origine.
4. `--gateway` et `--workspace` fournis l'emportent sur la mémoire ; les variables
   `CLAUDE_RUNNER_*` l'emportent aussi.
5. Sans mémoire et sans argument, le runner **refuse** avec un message qui nomme les deux gestes de
   réparation, et sort en `2`.
6. Avec une mémoire et un jeton **expiré**, le message nomme l'expiration ; le runner ne tente
   **aucun** appairage silencieux.
7. Une mémoire corrompue ne fait pas tomber le runner.
8. Les fichiers de reprise reçoivent des permissions restreintes sur POSIX (`rw-------`) — même
   traitement que le jeton, bien qu'ils ne portent pas de secret.
9. Toutes les commandes antérieures (trois arguments) continuent de fonctionner à l'identique.

---

## Plan de test

### Unitaires — `SessionMemoryTest`

- écriture puis relecture (aller-retour) ;
- le fichier écrit **ne contient pas** la valeur du jeton (recherche littérale dans le texte) ;
- localisation depuis le dossier lui-même, depuis un **sous-dossier** (remontée), et depuis le
  repli `~` ;
- priorité : mémoire de projet devant mémoire de `~` ;
- fichier corrompu → `Optional.empty()`, aucune exception ;
- racine mémorisée disparue → repli sur le dossier porteur de `.claude-runner` ;
- permissions POSIX restreintes ;
- écriture impossible → pas d'exception (best-effort).

### Unitaires — `RunnerConfigResumeTest`

- résolution **sans argument** à partir d'une mémoire ;
- CLI et environnement l'emportent sur la mémoire ;
- absence de mémoire → `ConfigException` dont le message nomme les deux gestes ;
- une mémoire ne dispense pas des validations existantes (racine inexistante refusée).

### Unitaires — `RunnerResumeMessagesTest`

- message de refus « rien de mémorisé » ;
- message de refus « jeton expiré » citant la date ;
- ligne console « Reprise : … » présente quand la configuration vient de la mémoire, absente sinon.

### Isolation

Sans objet côté données : SF-46-01 n'ouvre **aucun** accès en base ni au réseau au-delà de
l'existant. L'isolation qui compte ici est celle du **poste** : le fichier de reprise est écrit
sous le compte de l'utilisateur, avec des permissions restreintes, et ne porte aucun secret.

---

## Composants impactés

| Fichier | Nature |
|---|---|
| `runner/src/main/java/fr/claudegateway/runner/SessionMemory.java` | **Nouveau** — écriture, localisation et lecture de la mémoire de reprise |
| `runner/src/main/java/fr/claudegateway/runner/RunnerConfig.java` | Résolution : mémoire en dernier recours, messages de refus |
| `runner/src/main/java/fr/claudegateway/runner/RunnerMain.java` | Écrit la mémoire après appairage ; annonce la reprise |
| `runner/README.md` | Commande de reprise documentée |
| `runner/src/main/java/fr/claudegateway/runner/ResumeMessages.java` | **Nouveau** — messages de refus de reprise |
| `runner/src/main/java/fr/claudegateway/runner/TokenStore.java` | `expiredAt()` : distinguer « expiré » de « absent » |
| Tests : `SessionMemoryTest`, `RunnerConfigResumeTest`, `RunnerResumeMessagesTest` | — |

**Aucune** table, **aucune** migration Liquibase, **aucun** endpoint, **aucun** composant Angular.

---

## Contraintes de validation

| Champ | Contrainte |
|---|---|
| `gateway` (mémoire) | Revalidé par `normalizeGateway` comme s'il venait de la ligne de commande — une mémoire éditée à la main ne contourne rien |
| `workspaceRoot` (mémoire) | Revalidé : chemin absolu normalisé, existant, répertoire |
| Remontée des parents | Bornée par la racine du système de fichiers (pas de profondeur arbitraire configurable) |
| Nom de fichier | `session.json`, dans le `.claude-runner` déjà utilisé par le jeton |

---

## Hors périmètre

- Mémoriser le **proxy**, le libellé, le transport ou `--no-bash` : la reprise restitue le
  *contexte d'appairage*, pas les préférences d'exécution d'un lancement particulier.
- Démarrage automatique à l'ouverture de session (F-46 entière).
- Toute reprise qui contournerait l'expiration du jeton.
- Le double-clic et la commande affichée à l'écran → **SF-46-02**.

---

## Décisions prises en cours de dev (arbitrages tracés)

| # | Décision | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| **D1** | Précédence `CLI > env > mémoire` | Une machine où l'on a tapé quelque chose fait ce qui est tapé ; la mémoire ne doit jamais réécrire une intention explicite | Mémoire prioritaire (« elle sait mieux ») : rend un `--workspace` de dépannage inopérant | Oui |
| **D2** | La mémoire est un fichier **distinct** du jeton (`session.json`), et **sans secret** | C'est ce qui autorise la copie sous `~` — indispensable au double-clic (SF-46-02) — sans dupliquer le jeton en deux endroits | Enrichir `token.json` de deux champs : « à côté du jeton » au sens strict, mais alors la copie sous `~` aurait dupliqué le secret | Oui |
| **D3** | Aucun réappairage silencieux, aucune racine devinée | Un runner qui devine sa racine d'exécution écrira un jour au mauvais endroit ; un runner qui redemande un code en silence masque une expiration | Repli automatique sur le dossier courant | Oui |
| **D4** | Écriture de la mémoire en **best-effort** (`WARN`, jamais d'échec) | Un appairage qui vient de réussir ne doit pas échouer sur un dossier en lecture seule | Échec dur | Oui |
| **D5** | La mémoire **repasse par toutes les validations** de la ligne de commande | Un `session.json` édité à la main ne doit contourner ni la validation d'URL ni celle de la racine | Faire confiance au fichier qu'on a écrit soi-même | Oui |
| **D6** | Remontée des **répertoires parents** lors de la recherche | Le runner se lance en pratique depuis un sous-dossier du projet ; la racine vient de la mémoire, pas du dossier trouvé — la remontée ne peut donc pas élargir le confinement | Chercher uniquement dans le répertoire courant | Oui |
