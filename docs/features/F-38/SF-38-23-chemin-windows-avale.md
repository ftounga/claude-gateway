# Mini-spec — F-38 / SF-38-23 — Le chemin Windows avalé par le shell

## Identifiant

`F-38 / SF-38-23`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-38-23-chemin-windows-avale`

---

## Objectif

> Que la commande proposée par l'écran ne casse pas sous Git Bash, et que le runner **nomme** le
> problème quand elle a été tapée à la main.

---

## Déclencheur

Deuxième obstacle du même client, immédiatement après avoir installé Java 21 :

```
$ java -jar claude-runner.jar --gateway … --workspace C:\Users\U66YA96\dev\cagip --code …
[19:23:42] ERREUR  --workspace n'existe pas : C:\Users\U66YA96\dev\cagip\UsersU66YA96devcagip
```

**Le runner n'a rien fait de faux.** Sous Git Bash (MINGW64), `\U`, `\d` et `\c` sont des séquences
d'échappement : le shell a livré `C:UsersU66YA96devcagip`. Windows lit cela comme « lecteur `C:`,
chemin **relatif** », et le résout depuis le dossier courant — d'où le chemin doublé du message.

Le message d'erreur, lui, est inexploitable : il affiche un chemin que l'utilisateur n'a jamais
tapé, sans dire d'où il sort.

**Et l'écran d'appairage y conduit** : il compose `--workspace C:\Users\…` **sans guillemets**.

---

## Comportement attendu

### L'écran (la cause)

La commande affichée entoure le chemin de **guillemets** :

```
java -jar claude-runner.jar --gateway https://…/api --workspace "C:\Users\moi\projet" --code AB12CD
```

Les guillemets ne gênent aucun shell — ni `cmd`, ni PowerShell, ni Git Bash, ni un terminal Unix —
et suppriment le problème à la source, pour tous ceux qui copient la commande.

### Le runner (le rattrapage)

Quand le chemin fourni ressemble à une lettre de lecteur **immédiatement suivie** d'autre chose
qu'un séparateur (`C:Users…`), le runner nomme la cause au lieu d'afficher un chemin résolu
incompréhensible :

```
--workspace n'existe pas : C:\...\UsersU66YA96devcagip

Le chemin semble avoir perdu ses séparateurs : « C:UsersU66YA96devcagip ».
Sous Git Bash, les antislashs d'un chemin Windows sont interprétés comme des échappements.
Entourez le chemin de guillemets :  --workspace "C:\Users\...\projet"
ou utilisez des barres obliques :   --workspace C:/Users/.../projet
```

Le comportement ne change pas — le lancement échoue toujours. **Seul le message change.**

### Cas d'erreur

| Situation | Comportement |
|-----------|--------------|
| Chemin Unix inexistant | Message actuel, inchangé — la piste Windows n'aurait aucun sens |
| Chemin Windows correct mais absent | Message actuel, inchangé |
| Lettre de lecteur sans séparateur | Message actuel **plus** l'explication |

---

## Critères d'acceptation

- [ ] La commande affichée par l'écran entoure le chemin de guillemets.
- [ ] Un chemin `C:Users…` déclenche l'explication, avec les deux corrections possibles.
- [ ] Un chemin `C:\Users\…` ou `C:/Users/…` absent garde le message **actuel**, sans explication
      hors sujet.
- [ ] Un chemin Unix absent garde le message actuel.
- [ ] Le code de sortie et le fait d'échouer sont **inchangés**.

---

## Périmètre

### Hors scope

- Corriger le chemin à la place de l'utilisateur : deviner qu'il voulait `C:\Users\…` reviendrait à
  inventer des séparateurs. Un chemin d'exécution ne se devine pas.
- Les autres shells et leurs échappements.

---

## Technique

| Classe | Changement |
|--------|-----------|
| `runner/RunnerConfig` | Détection du symptôme, message enrichi |
| `runner-pairing-dialog.component` (écran) | Guillemets autour du chemin |

### Migration Liquibase

- [x] **Non applicable.**

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | Aucune route ; seule la chaîne de commande affichée change |

---

## Plan de test

### Tests unitaires (runner)

- [ ] `C:Users\projet` produit l'explication.
- [ ] `C:\Users\projet` absent produit le message actuel, **sans** l'explication.
- [ ] Un chemin Unix absent produit le message actuel.
- [ ] L'échec reste un échec (`ConfigException`).

### Tests frontend

- [ ] La commande affichée contient `--workspace "…"`, guillemets compris.
- [ ] Le chemin par défaut affiché est également entre guillemets.

### Isolation workspace

- [x] Non applicable.

---

## Notes et décisions

**D1 — Corriger la cause avant le symptôme.** L'explication du runner ne sert qu'à ceux qui tapent
la commande à la main ; les guillemets dans l'écran suppriment le problème pour tous les autres.
Livrer seulement le message aurait laissé le piège en place.

**D2 — Ne rien deviner.** On pourrait tenter de reconstruire `C:\Users\...` à partir de
`C:UsersU66YA96devcagip` — c'est indécidable, et un runner qui devine sa racine d'exécution est un
runner qui écrira un jour au mauvais endroit.

**D3 — Le message montre les deux sorties.** Guillemets **ou** barres obliques : la seconde survit
au copier-coller entre shells, la première est celle que l'écran propose. Donner une seule des deux
laisserait la moitié des situations sans réponse.
