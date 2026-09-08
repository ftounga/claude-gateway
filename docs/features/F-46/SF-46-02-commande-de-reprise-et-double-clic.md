# Mini-spec — F-46 / SF-46-02 — La commande de reprise, et le lanceur qui se double-clique

## Identifiant

`F-46 / SF-46-02`

## Feature parente

`F-46` — Reprendre le runner sans le réinstaller

## Statut

`in-review`

## Date de création

2026-09-08

## Branche Git

`feat/SF-46-02-commande-de-reprise`

---

## Objectif

> Que l'écran donne, **à côté** de la commande d'installation, la commande de **reprise** — et que
> le lanceur du paquet autonome démarre au **double-clic**, sans argument, sur une machine déjà
> appairée.

---

## Déclencheur

SF-46-01 a rendu la reprise possible : `java -jar claude-runner.jar`, sans argument, depuis un
projet appairé. Mais **personne ne le sait**. L'écran « Connecter une machine » n'affiche qu'une
seule commande — celle du **premier** lancement, avec ses trois arguments et son code d'appairage —
et c'est elle que l'utilisateur recopie tous les matins.

Le paquet autonome pose le même problème d'un cran plus haut : son lanceur (`claude-runner.cmd`,
`claude-runner.command`) existe précisément pour être **double-cliqué**, mais un double-clic ne
transmet aucun argument. Jusqu'ici il échouait donc systématiquement sur `--gateway est requis`,
et sous Windows la fenêtre se refermait avant que le message soit lisible.

---

## Comportement attendu

### Cas nominal — l'écran

L'étape 4 (« Lancer le runner ») présente **deux** commandes, dans cet ordre et clairement
distinguées :

| Commande | Libellé | Contenu |
|---|---|---|
| Installation | « La première fois » | Inchangée : lanceur + `--gateway` + `--workspace` + `--code` |
| Reprise | « Les fois suivantes » | Le lanceur **seul**, précédé d'un `cd` vers la racine saisie |

Exemples, selon le format retenu :

```
cd "/home/moi/projet" && java -jar claude-runner.jar
cd "C:\Users\moi\projet" && claude-runner.cmd
cd "/Users/moi/projet" && ./claude-runner.command
```

Chaque commande a son propre bouton « copier ». Une phrase dit **pourquoi** cela suffit : l'appairage
a mémorisé la passerelle et la racine ; le code, lui, ne sert qu'une fois.

Quand un **paquet autonome** est retenu, l'écran ajoute la mention du **double-clic** : le lanceur
du paquet démarre le runner sans qu'aucune commande soit tapée.

### Cas nominal — les lanceurs

- **Windows** (`claude-runner.cmd`) : transmet toujours `%*` (aucun argument au double-clic, tous
  les arguments quand il est appelé depuis un terminal). En cas d'**échec** et **seulement** quand
  il a été double-cliqué, il **attend une touche** avant de se refermer — sinon le message d'erreur
  disparaît avec la fenêtre.
- **macOS** (`claude-runner.command`) : inchangé dans son principe (il transmet déjà `"$@"`) ; la
  levée de quarantaine reste faite au premier lancement.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Aucun chemin de projet saisi | La commande de reprise utilise le **chemin d'exemple**, comme la commande d'installation — visiblement incomplète plutôt que faussement prête |
| Aucun code utilisable (non généré / expiré) | La commande d'**installation** garde son marqueur `<code-appairage>` ; la commande de **reprise**, elle, reste **valide** — elle n'a jamais eu besoin de code |
| Double-clic sur une machine jamais appairée | Le runner refuse avec le message de SF-46-01 ; sous Windows, la fenêtre **reste ouverte** pour qu'il soit lu |
| Paquet retenu mais non servi par la gateway | Comportement existant conservé (D3 de SF-44-02) : pas de bouton, pas de commande de paquet |

---

## Critères d'acceptation

1. L'étape 4 affiche **deux** commandes distinctes, chacune avec son bouton de copie.
2. La commande de reprise ne contient **ni `--gateway`, ni `--workspace`, ni `--code`**.
3. Elle commence par un `cd` vers le chemin saisi (ou le chemin d'exemple), **entre guillemets**
   (même piège Git Bash qu'en SF-38-23).
4. Elle utilise le **même lanceur** que la commande d'installation (jar, `.cmd` ou `.command`) selon
   le format retenu.
5. Un code expiré n'altère **pas** la commande de reprise.
6. La mention du double-clic apparaît **si et seulement si** un paquet autonome est réellement servi.
7. Le lanceur Windows du paquet reste en CRLF, transmet `%*`, et **ne se referme pas** sur un échec
   quand il a été double-cliqué.
8. Aucune couleur, police ou espacement hors `docs/DESIGN_SYSTEM.md`.

---

## Plan de test

### Unitaires — `runner-pairing-dialog.component.spec.ts` (complété)

- la commande de reprise ne porte aucun des trois arguments ;
- elle porte le `cd` et les guillemets, avec le chemin saisi ;
- elle bascule sur le chemin d'exemple quand rien n'est saisi ;
- elle suit le format retenu (jar / `.cmd` / `.command`) ;
- un code expiré laisse la commande de reprise inchangée alors qu'il altère celle d'installation ;
- la mention du double-clic n'apparaît que si un paquet est réellement servi.

### Unitaires — `WindowsPackageTest` (complété, actif quand le paquet est construit)

- le lanceur contient la garde de fenêtre (`pause` conditionnel) et reste en CRLF.

### Isolation

Sans objet : SF-46-02 n'ajoute **aucun** appel réseau, aucun endpoint, aucun accès aux données. Le
chemin du projet **ne quitte pas le navigateur** (règle déjà posée en SF-38-15).

---

## Composants impactés

| Fichier | Nature |
|---|---|
| `frontend/src/app/atelier/runner/runner-pairing-dialog.component.ts` | `resumeCommand()` + mention du double-clic |
| `frontend/src/app/atelier/runner/runner-pairing-dialog.component.html` | Deux commandes à l'étape 4 |
| `frontend/src/app/atelier/runner/runner-pairing-dialog.component.scss` | Libellés des deux blocs de commande |
| `runner/package-windows.sh` | Lanceur : garde de fenêtre au double-clic |
| `runner/src/test/java/fr/claudegateway/runner/WindowsPackageTest.java` | Vérifie la garde |
| Specs frontend | Cas ci-dessus |

**Aucune** table, **aucune** migration, **aucun** endpoint.

---

## Contraintes de validation

| Élément | Contrainte |
|---|---|
| Chemin dans le `cd` | Toujours entre guillemets (Git Bash, SF-38-23) ; jamais pré-rempli, l'exemple sert de repli |
| Lanceur | Strictement le même que la commande d'installation — deux lanceurs différents pour la même machine seraient une invitation à l'erreur |
| Design system | Palette et typographies existantes du dialogue ; aucun nouveau composant Material |

---

## Hors périmètre

- Démarrage automatique à l'ouverture de session (F-46 entière).
- Un raccourci créé sur le bureau par le produit : c'est de l'installation.
- Toute détection, depuis le navigateur, de ce qui est déjà mémorisé sur le poste : le bac à sable
  web l'interdit, et le prétendre serait pire que se taire (limite déjà posée par F-45).

---

## Décisions prises en cours de dev (arbitrages tracés)

| # | Décision | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| **D1** | Deux commandes **étiquetées** (« La première fois » / « Les fois suivantes ») plutôt qu'un onglet ou un bouton bascule | Deux blocs sombres consécutifs sans étiquette se lisent comme une seule commande coupée en deux ; et les deux sont utiles au même moment (installer aujourd'hui, reprendre demain) | Un sélecteur : cache la moitié de l'information au moment où elle se comprend | Oui |
| **D2** | La commande de reprise **ignore** l'état du code d'appairage | Elle ne s'en sert pas ; un code expiré n'a aucune raison de la rendre illisible | Marqueur `<code-appairage>` partout, par symétrie | Oui |
| **D3** | `cd "<racine>" && <lanceur>` plutôt que le lanceur seul | La reprise dépend du **répertoire courant** (SF-46-01) : donner le lanceur seul, c'est donner une commande qui échoue partout ailleurs | Afficher le lanceur seul avec une phrase « depuis le dossier du projet » | Oui |
| **D4** | Pause Windows **conditionnée** au double-clic **et** à l'échec | Une pause inconditionnelle bloquerait tout appel depuis un terminal et toute automatisation | `pause` systématique en fin de script | Oui |
| **D5** | Mention du double-clic seulement si un paquet est **réellement servi** | Un `.jar` seul ne se double-clique pas utilement : il lui faudrait la JVM du système, celle que le paquet existe pour remplacer | Annoncer le double-clic dans tous les cas | Oui |
