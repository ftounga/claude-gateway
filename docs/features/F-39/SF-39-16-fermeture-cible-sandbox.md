# Mini-spec — F-39 / SF-39-16 — Fermer la cible `SANDBOX` de la boucle maison

## Identifiant

`F-39 / SF-39-16`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-16-fermeture-cible-sandbox`

---

## Objectif

> Fermer le chemin par lequel la boucle maison exécutait ses outils sur le **stockage objet** —
> devenu sans usage produit depuis que l'écran choisit son moteur — sans supprimer le socle sur
> lequel toute la boucle est testée.

---

## Déclencheur

**Lot 9**, dernier du cadrage F-39. La décision D7 y était laissée ouverte, et c'est important :

> *« La cible `SANDBOX` de la boucle maison (`executeToolOnStorage`) n'a plus d'usage produit. Son
> retrait est une subfeature de nettoyage à part, **à décider** — pas un effet de bord. »*

Depuis le lot 4, l'écran ne l'atteint plus jamais : `AtelierEngineService` résout le moteur, et
l'écran envoie vers la boucle maison **uniquement** quand un runner est là (`localEngine()`). Un
projet sans runner passe par les Managed Agents. Le chemin « boucle maison sur stockage » est donc
mort **côté produit**, mais parfaitement vivant côté API : `/chat` et `/chat/stream` l'acceptent
encore.

---

## Ce que l'on ferme, et ce que l'on garde

C'est le cœur de l'arbitrage, et il mérite d'être posé franchement.

**Supprimer le code** aurait coûté cher pour un gain symbolique. `executeToolOnStorage` est le socle
sur lequel **toute la boucle** est testée : mémoire de la trajectoire, cache de prompt, édition
ciblée, plan, plafond de dépense, écartement de contexte — une trentaine de tests unitaires
l'exercent sans avoir besoin d'un runner simulé. Le supprimer aurait détruit cette couverture pour
retirer un chemin que personne n'emprunte déjà.

**Ne rien faire** aurait laissé l'ambiguïté que tout le chantier F-39 cherche à lever : deux chemins
d'exécution pour la même intention, dont un accessible par API seulement.

**Ce qui est livré** : un **coupe-circuit**, `app.atelier.storage-execution`, **fermé par défaut**.
La porte produit se ferme ; le socle de test reste, et s'ouvre explicitement là où il sert.

C'est le même patron que `app.atelier.context-pruning` (SF-39-12) : une capacité qu'on veut pouvoir
rétablir par variable d'environnement, sans livraison.

---

## Comportement attendu

### Cas nominal

Inchangé pour tout ce que l'écran fait aujourd'hui : un projet avec runner passe par la boucle
maison, un projet sans runner par les Managed Agents.

### Cible `SANDBOX` sur la boucle maison

Un appel de la boucle maison sur un projet dont la cible n'est pas `RUNNER` est **refusé avant tout
appel fournisseur**, avec un message qui dit où le travail doit se faire :

> « Ce projet n'a pas de machine connectée : son terminal passe par le bac à sable hébergé. »

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `/chat` sur un projet en cible `SANDBOX` | Refus lisible, **aucun appel fournisseur**, aucun token consommé | 409 |
| `/chat/stream` idem | Même refus, relayé dans le flux comme les autres erreurs | 200 (SSE `error`) |
| Coupe-circuit ouvert (`true`) | Comportement d'avant, à l'identique | 200 |
| Projet en cible `RUNNER` | Inchangé | 200 |

---

## Critères d'acceptation

- [ ] Le coupe-circuit `app.atelier.storage-execution` existe et vaut **`false`** par défaut.
- [ ] Fermé, un appel de la boucle maison sur une cible `SANDBOX` est refusé **avant** tout appel au
      fournisseur (vérifié par compteur d'appels : zéro).
- [ ] Le refus ne consomme aucun token et ne persiste aucun message.
- [ ] Ouvert, le comportement est **strictement** celui d'avant (test de non-régression).
- [ ] Un projet en cible `RUNNER` n'est jamais affecté.
- [ ] Le message de refus dit où le travail se fait, pas ce qui a échoué.
- [ ] Isolation `user_id` inchangée : `requireOwned` reste le premier geste, **avant** le refus.

---

## Périmètre

### Hors scope

- La **suppression** de `executeToolOnStorage` et des outils `list_files` / `search_files` en cible
  `SANDBOX` : ils restent le socle de test de la boucle (voir §Ce que l'on ferme).
- Le chat sur fichiers du menu **Chat** (F-02), qui n'est pas concerné et ne l'a jamais été.
- Tout changement d'écran : l'écran n'atteignait déjà plus ce chemin.

---

## Technique

### Endpoint(s)

Aucun ajouté. Deux endpoints existants gagnent un refus.

### Migration Liquibase

- [x] **Non applicable.**

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `atelier/AtelierProperties` | `storageExecution` (défaut `false`) |
| `atelier/AtelierChatService` | Refus en tête de `runLoop`, après `requireOwned` |
| `application.yml` | `storage-execution: ${APP_ATELIER_STORAGE_EXECUTION:false}` |

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| **Contexte tenant** | **Oui** | Le refus est posé **après** `requireOwned` : un projet d'autrui rend toujours 404, jamais 409 — l'ordre importe, sans quoi le refus révélerait l'existence d'un projet qu'on ne possède pas. |
| **Plans / limites** | **Oui** | Le refus intervient **avant** `assertWithinQuota` et avant tout appel fournisseur : aucun token n'est consommé, aucun usage n'est enregistré. Vérifié par test. |
| Navigation / routing | Non | Aucun écran touché |

---

## Plan de test

### Tests unitaires

- [ ] Coupe-circuit fermé + cible `SANDBOX` ⇒ refus, **zéro appel** au fournisseur, aucun message
      persisté.
- [ ] Coupe-circuit fermé + cible `RUNNER` ⇒ inchangé.
- [ ] Coupe-circuit ouvert + cible `SANDBOX` ⇒ comportement d'avant (non-régression).
- [ ] Projet d'autrui ⇒ 404, jamais 409 (l'ordre des gardes).
- [ ] `AtelierPropertiesTest` — défaut `false`, valeur `true` honorée.

### Tests d'intégration

- [ ] Couverts par les tests existants, qui ouvrent le coupe-circuit pour exercer la boucle.

### Isolation workspace

- [x] Applicable — test explicite de l'ordre `requireOwned` puis refus.

---

## Notes et décisions

**D1 — Un coupe-circuit plutôt qu'une suppression.** Voir §Ce que l'on ferme. Le point décisif :
supprimer le chemin aurait supprimé le socle de test d'une trentaine de vérifications qui n'ont rien
à voir avec la cible d'exécution. On ferme la porte, on garde l'atelier.

**D2 — Fermé par défaut, ouvert dans les tests.** Le défaut décrit ce que la production doit faire.
Les tests qui exercent la boucle l'ouvrent explicitement, ce qui rend visible, dans chaque fichier
de test, qu'on y emprunte un chemin que le produit n'emprunte plus.

**D3 — Le refus vient après `requireOwned`, jamais avant.** Un refus posé en premier dirait « ce
projet existe mais n'est pas configuré ainsi » à quelqu'un qui n'en est pas propriétaire. L'ordre
des gardes est une règle de confidentialité, pas de style.
