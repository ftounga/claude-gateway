# Mini-spec — F-38 / SF-38-19 — Le runner exécute par défaut

## Identifiant

`F-38 / SF-38-19`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`livrée` — PR #249, mergée le 2026-09-06

## Date de création

2026-09-06

## Branche Git

`feat/SF-38-19-bash-par-defaut`

---

## Objectif

> Inverser le drapeau d'exécution : un runner **exécute** des commandes par défaut, et
> `--no-bash` le restreint à la lecture — parce que la garde réelle est la porte de confirmation,
> pas ce drapeau.

---

## Déclencheur

Banc d'essai, deuxième échec en dix minutes. Après avoir corrigé l'URL d'appairage (PR #244), le
runner se connecte, l'agent démarre… et s'arrête net :

> « L'exécution de commandes n'est pas activée sur ce runner. Redémarre-le avec `--allow-bash`. »

Le mode runner **existe pour exécuter sur la machine de l'utilisateur**. Sans `bash`, il ne reste que
quatre outils fichiers — c'est-à-dire moins que ce que le bac à sable hébergé fait déjà, et sans
aucune des raisons qui justifient d'installer un binaire. Le drapeau fermé par défaut produit donc un
runner qui se connecte correctement et ne sert à rien, avec un message que seul un agent a su
diagnostiquer.

Décision du product owner : *« le `--allow-bash` devrait être mis par défaut »*.

---

## Ce que le drapeau protégeait vraiment

C'est la question à poser avant de le retourner, et la réponse est : **pas grand-chose**.

| Barrière | Nature | Désactivable ? |
|---|---|---|
| **Porte de confirmation** (SF-38-08) | Chaque commande demande une autorisation à l'écran | **Non**, jamais en mode runner |
| Journal d'audit (SF-38-08) | Toute commande tracée, refus compris | Non |
| Exclusions `.runnerignore` (SF-38-10) | Chemins protégés | Non |
| Coupe-circuit | Révocation immédiate des jetons | — |
| `--allow-bash` | Drapeau au lancement | Oui, par l'utilisateur |

Le drapeau était une **seconde serrure devant une porte déjà verrouillée**. Et il s'adressait à
quelqu'un qui a déjà : téléchargé un binaire, généré un code d'appairage sur *son* projet, et lancé
le jar sur *sa* machine. À ce stade, le consentement est acquis ; ce que le drapeau ajoutait, c'est
une friction, pas une décision.

---

## Comportement attendu

### Cas nominal

Un runner lancé sans option supplémentaire **exécute** les commandes que l'agent demande — chacune
soumise à la porte de confirmation, comme aujourd'hui.

### Restreindre à la lecture

`--no-bash` (ou `CLAUDE_RUNNER_NO_BASH=true`) rend un runner **en lecture seule** : les outils
fichiers fonctionnent, `bash` est refusé avec le message existant. C'est le cas rare — auditer un
dépôt sensible, explorer sans laisser exécuter — et il conserve donc son moyen.

### Console

Le mode est **dit au démarrage**, dans les deux sens : on ne découvre pas au premier refus qu'on
tourne en lecture seule.

```
Commandes : autorisées (chaque commande demande votre autorisation)
```
```
Commandes : refusées (--no-bash) — seuls les outils fichiers sont disponibles
```

### Compatibilité

`--allow-bash` reste **accepté et sans effet** : les commandes copiées d'un écran ancien, d'un script
ou d'une note continuent de fonctionner. Le retirer aurait fait échouer au démarrage des lignes de
commande parfaitement valides la veille.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `--no-bash` **et** `--allow-bash` ensemble | `--no-bash` l'emporte : entre deux consignes contradictoires, on retient la plus restrictive |
| `bash` demandé sur un runner en lecture seule | Message existant, inchangé, rendu à l'agent comme résultat d'outil |
| Variable d'environnement mal formée | Traitée comme absente ; le runner démarre |

---

## Critères d'acceptation

- [ ] Un runner lancé sans option exécute les commandes.
- [ ] `--no-bash` le restreint à la lecture.
- [ ] `CLAUDE_RUNNER_NO_BASH=true` a le même effet.
- [ ] `--allow-bash` est accepté sans effet (compatibilité).
- [ ] `--no-bash` **et** `--allow-bash` ⇒ lecture seule.
- [ ] Le mode est affiché au démarrage, dans les deux sens.
- [ ] La porte de confirmation reste appliquée à **chaque** commande — rien de ce changement ne la
      touche.
- [ ] `runner/README.md` et l'écran d'appairage disent le nouveau défaut.

---

## Périmètre

### Hors scope

- Toute modification de la porte de confirmation, du journal d'audit ou des exclusions : ce sont eux
  qui protègent, et ils ne changent pas.
- Un réglage côté gateway pour interdire `bash` à distance : ce serait une autre feature (et une
  promesse que le runner, qui tourne chez l'utilisateur, ne peut pas tenir seul).

---

## Technique

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `runner/RunnerConfig` | `--no-bash` / `CLAUDE_RUNNER_NO_BASH` ; `--allow-bash` toléré ; défaut inversé |
| `runner/RunnerMain` | Mode affiché au démarrage |
| `runner/README.md` | Nouveau défaut, et ce qui protège réellement |
| `frontend runner-pairing-dialog` | La commande n'a plus besoin du drapeau ; une note dit comment restreindre |

### Migration Liquibase

- [x] **Non applicable.**

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | Aucun chemin d'accès aux données modifié |
| Plans / limites | Non | Aucune consommation, aucun gate |
| Navigation / routing | Non | — |
| **Sécurité (hors grille, mais central)** | **Oui** | La garde reste la **porte de confirmation**, non désactivable, plus l'audit, les exclusions et le coupe-circuit. Ce changement retire une friction, pas une barrière — et le README doit le dire sans ambiguïté. |

---

## Plan de test

### Tests unitaires

- [ ] Sans option ⇒ `allowBash()` vrai.
- [ ] `--no-bash` ⇒ faux.
- [ ] `CLAUDE_RUNNER_NO_BASH=true` ⇒ faux.
- [ ] `--allow-bash` seul ⇒ vrai (compatibilité).
- [ ] `--allow-bash --no-bash` ⇒ **faux** (la restriction l'emporte).
- [ ] Valeur d'environnement mal formée ⇒ traitée comme absente.

### Tests frontend

- [ ] La commande affichée ne porte plus de drapeau d'exécution.

### Isolation workspace

- [x] Non applicable — aucun accès aux données n'est touché.

---

## Notes et décisions

**D1 — Inverser plutôt que supprimer.** Le cas « runner en lecture seule » est légitime : auditer un
dépôt sensible, laisser un agent explorer sans exécuter. Il devient rare, il ne disparaît pas. Un
défaut sert le cas courant, une option sert le cas rare — c'était exactement l'inverse.

**D2 — `--allow-bash` reste toléré.** Le retirer ferait échouer au démarrage des lignes de commande
valides la veille : celles copiées d'un écran, d'un script, d'une note. Une option sans effet ne
coûte rien ; un démarrage refusé coûte une session.

**D3 — La restriction l'emporte sur l'autorisation.** Deux consignes contradictoires sur une même
ligne de commande, c'est une erreur de l'opérateur ; en cas de doute, on retient la plus stricte.

**D4 — Le mode est dit au démarrage, dans les deux sens.** Le défaut d'aujourd'hui vient de ce qu'un
runner restreint ne se signalait pas : on le découvrait au premier refus, à travers un message que
seul un agent a su interpréter.
