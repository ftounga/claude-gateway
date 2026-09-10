# Mini-spec — F-57 / SF-57-01 — La déclaration de démarrage

## Identifiant

`F-57 / SF-57-01`

## Feature parente

`F-57` — Transparence sur le poste de travail

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-57-01-declaration-demarrage`

---

## Objectif

Au démarrage, le runner **déclare en un bloc** ce qu'il fait, sous quels droits, par quelle route
réseau, et qu'il ne cherche pas à savoir ce qui observe le poste.

---

## Comportement attendu

### Cas nominal

Après la configuration résolue et avant l'appairage, le runner affiche un bloc de transparence
composé de quatre lignes stables, dans cet ordre :

```
Ce runner : execute sur cette machine les commandes que vous autorisez depuis l'Atelier,
            dans le dossier du projet vise — rien d'autre, et rien sans votre geste.
Compte    : francky
Route     : directe (aucun proxy declare dans ce terminal)
Rappel    : sur un poste d'entreprise, vos commandes sont vraisemblablement journalisees par
            votre employeur. Ce runner ne cherche pas a savoir ce qui observe ce poste, et ne
            le fera pas.
```

La ligne **Route** dépend de ce que le runner lit **de sa propre configuration**, jamais de l'état
du poste :

| Situation | Ligne affichée |
|---|---|
| Ni `HTTPS_PROXY` ni `HTTP_PROXY` | `directe (aucun proxy declare dans ce terminal)` |
| Proxy déclaré sur `127.0.0.1` / `localhost` | `relais local <hote:port> (HTTPS_PROXY), qui porte l'authentification a la place du runner` |
| Proxy déclaré ailleurs | `proxy d'entreprise <hote:port> (HTTPS_PROXY)` |
| Proxy déclaré par `HTTP_PROXY` seulement | idem, la variable citée est `HTTP_PROXY` |

La ligne **Compte** reprend `Privileges` (SF-38-18) : le nom du compte, suivi de `(administrateur)`
quand le compte est élevé. L'avertissement `root` existant de `RunnerMain` est **conservé tel quel**.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| `HTTPS_PROXY` porte des identifiants (`http://user:motdepasse@hote:3128`) | La ligne affiche `hote:3128` — **jamais** l'identifiant ni le mot de passe |
| `HTTPS_PROXY` illisible (port non numérique, valeur tronquée) | La ligne affiche la valeur **expurgée de son `userinfo`** sans lever ; le démarrage se poursuit |
| `user.name` absent de la JVM | La ligne `Compte` affiche `(compte inconnu)`, le démarrage se poursuit |
| Environnement `null` | Aucune exception : route `directe` |

Aucun de ces cas n'interrompt le démarrage : une information sur la transparence ne doit jamais
coûter la connexion (même règle que `Privileges`).

---

## Critères d'acceptation

- [ ] Le bloc est produit par **une seule** fonction pure et testable, sans effet de bord ni I/O.
- [ ] Le bloc dit **ce que le runner fait** : exécuter les commandes autorisées, dans le dossier du
      projet visé.
- [ ] Le bloc dit **sous quel compte** il tourne, et signale un compte administrateur.
- [ ] Le bloc dit **par quelle route** il sort : directe, proxy d'entreprise, ou relais local, en
      nommant la variable d'environnement qui l'a décidé.
- [ ] Un proxy portant des identifiants est affiché **sans** son `userinfo` (test dédié).
- [ ] Le bloc porte la phrase de responsabilité : commandes **vraisemblablement journalisées** par
      l'employeur sur un poste d'entreprise.
- [ ] Le bloc affirme explicitement que le runner **ne cherche pas** à savoir ce qui observe le poste.
- [ ] Aucune ligne de code n'énumère un service, un pilote, un processus ou une clé de registre
      (vérifié en review : hors périmètre F-57).
- [ ] `RunnerMain` affiche ce bloc au démarrage, avant l'appairage, et ne duplique plus la ligne
      `Compte` ni la ligne `Proxy d'entreprise détecté`.

---

## Périmètre

### Hors scope (explicite)

- Toute détection de l'état du poste (sécurité, antivirus, EDR, journalisation effective).
- L'interception TLS — c'est SF-57-02.
- Le rappel périodique côté application — c'est SF-57-03.
- La remontée de ces informations à la gateway : le bloc est **affiché**, il n'est pas transmis.

---

## Valeurs initiales

Sans objet : aucune entité, aucun état persisté.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|---|---|---|---|---|---|
| `userName` (lu de la JVM) | Non | — | texte libre ; vide ⇒ `(compte inconnu)` | Non | `trim()` |
| Valeur de `HTTPS_PROXY` / `HTTP_PROXY` | Non | — | URL de proxy ; `userinfo` **supprimé** avant affichage | Non | `trim()`, schéma retiré, `userinfo` retiré, chemin retiré |

Notes :
- « Relais local » = hôte `127.0.0.1`, `::1` ou `localhost`, quel que soit le port.
- Le bloc est en français, rendu par `ConsoleEncoding` comme toute autre ligne de la console.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun.

### Classes runner

| Classe | Opération |
|---|---|
| `StartupDisclosure` (nouvelle) | Produit les lignes du bloc — fonction pure |
| `ProxyResolver` | Expose la route **expurgée** (`routeDescription()`) |
| `Privileges` | Réutilisé tel quel |
| `RunnerMain` | Affiche le bloc, retire les lignes désormais redondantes |

---

## Plan de test

### Tests unitaires

- [ ] `StartupDisclosure` — nominal : le bloc dit l'action, le compte, la route et le rappel.
- [ ] `StartupDisclosure` — compte élevé : la mention `(administrateur)` apparaît.
- [ ] `StartupDisclosure` — compte vide : `(compte inconnu)`, aucune exception.
- [ ] `StartupDisclosure` — route directe quand aucun proxy n'est déclaré.
- [ ] `StartupDisclosure` — route « relais local » sur `127.0.0.1`.
- [ ] `StartupDisclosure` — route « proxy d'entreprise » ailleurs, variable nommée.
- [ ] `StartupDisclosure` — **sécurité** : un `HTTPS_PROXY` avec `user:motdepasse@` n'affiche ni
      l'un ni l'autre.
- [ ] `StartupDisclosure` — le bloc contient la phrase « ne cherche pas ».
- [ ] `ProxyResolver` — `routeDescription()` expurge le `userinfo` et tolère une valeur malformée.

### Tests d'intégration

Sans objet : le runner n'expose aucun endpoint. Le point d'intégration est `RunnerMain`, couvert par
l'assertion sur les lignes produites (`StartupDisclosureTest`).

### Isolation workspace

- [x] Non applicable — raison : aucune donnée utilisateur n'est lue ni écrite ; le bloc est
      strictement local au processus runner.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-18` (Privileges) — statut : done
- `SF-38-25` / `SF-45-04` (préflight réseau, proxy) — statut : done

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

| # | Décision | Pourquoi |
|---|---|---|
| D1 | Un **bloc unique** plutôt que des lignes dispersées | Trois informations qui répondent à la même question (« que fait ce programme sur ma machine ? ») doivent se lire ensemble. Dispersées, elles se perdent dans le journal de démarrage. |
| D2 | La route est décrite depuis **notre configuration**, jamais depuis le poste | Lire le registre ou le fichier PAC pour « mieux dire » la route reviendrait à inspecter le poste — la ligne rouge de F-57 (cadrage, décision 1). |
| D3 | Le `userinfo` d'un proxy est **retiré** avant affichage | Un écran de transparence qui divulgue le mot de passe du proxy d'entreprise serait une régression de sécurité. Le cas est réel : `http://DOMAINE\user:mdp@proxy:8080` est une forme courante. |
| D4 | Le rappel « journalisées par votre employeur » est **aussi** dans le runner, pas seulement dans l'application | Le runner est ce qui tourne sur le poste, et il est parfois lancé sans que l'écran soit ouvert. Le rappel doit être là où l'exécution a lieu. |
