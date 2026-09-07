# Mini-spec — F-44 / SF-44-02 — Servir le paquet autonome et le proposer

## Identifiant

`F-44 / SF-44-02`

## Feature parente

`F-44` — Runner sans prérequis Java (Windows x64)

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-44-02-servir-et-proposer`

---

## Objectif

> Rendre le paquet autonome téléchargeable, et le proposer **en premier** sur Windows — sans retirer
> le `.jar` à ceux pour qui il reste le meilleur format.

---

## Comportement attendu

### Cas nominal

| Route | Sert | Nom du fichier |
|---|---|---|
| `GET /runner/download` | le `.jar` — **inchangé** | `claude-runner.jar` |
| `GET /runner/download/windows` | le paquet autonome | `claude-runner-windows-x64.zip` |

Les deux sont **publiques**, comme aujourd'hui : ce sont des clients, pas des données utilisateur —
ils ne contiennent ni jeton ni secret, l'appairage vient après.

L'écran d'appairage propose les deux, dans cet ordre :

1. **Windows, sans rien installer** — « ~39 Mo, contient Java. Décompressez et lancez
   `claude-runner.cmd`. »
2. **Le fichier `.jar`** — « 2,5 Mo, nécessite Java 21 déjà installé. »

La commande affichée s'adapte au format choisi : `claude-runner.cmd --gateway … --code …` pour le
paquet, `java -jar claude-runner.jar …` pour le jar.

### Cas d'erreur

| Situation | Comportement | Code |
|-----------|--------------|------|
| Paquet non empaqueté dans l'image | **404 `runner_package_unavailable`**, message distinct de celui du jar | 404 |
| Chemin non configuré | 404, même réponse | 404 |
| Fichier illisible | 404 — jamais une erreur serveur | 404 |

**L'écran n'affiche pas une option qui échouerait** : il interroge la disponibilité et masque le
format absent. Une gateway qui n'empaquette pas le ZIP (déploiement plus ancien) reste utilisable
avec le seul jar.

---

## Critères d'acceptation

- [ ] `GET /runner/download` est **inchangé** : même contenu, même nom, même code.
- [ ] `GET /runner/download/windows` sert le ZIP avec le bon nom de fichier.
- [ ] Le paquet absent donne un 404 au **code distinct** (`runner_package_unavailable`).
- [ ] Les deux routes restent publiques et passent par la chaîne de sécurité `/runner/**`.
- [ ] L'écran propose le paquet **en premier**, avec sa taille et son absence de prérequis.
- [ ] L'écran **masque** un format indisponible au lieu d'offrir un lien qui échoue.
- [ ] La commande affichée correspond au format choisi.
- [ ] **Design system** : couleurs et polices de `DESIGN_SYSTEM.md`, aucun `window.confirm`.

---

## Périmètre

### Hors scope

- macOS et Linux.
- La signature de code Windows (SmartScreen avertira au premier lancement).
- Toute modification du protocole d'appairage : le paquet et le jar parlent le même.

---

## Technique

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `runner/RunnerDownloadController` | Route `/download/windows`, chemin configurable, 404 dédié |
| `runner/RunnerProperties` (ou config) | `app.runner.windows-package-path` |
| `application.yml` | `APP_RUNNER_WINDOWS_PACKAGE_PATH`, défaut aligné sur l'image |
| `RunnerStatusResponse` ou route dédiée | Disponibilité des formats pour l'écran |
| `runner-pairing-dialog.component` | Deux formats, commande adaptée |

### Migration Liquibase

- [x] **Non applicable.**

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| **Auth / Principal** | **Oui** | La nouvelle route est **publique**, comme `/runner/download`. Elle doit passer par `RunnerSecurityConfig` (chaîne `/runner/**`) et **non** par la chaîne principale. À vérifier : aucun filtre utilisateur traversé, et la route n'apparaît pas dans les chemins authentifiés. |
| Contexte tenant | Non | Le paquet est identique pour tous : aucune donnée utilisateur |
| Plans / limites | Non | Le téléchargement n'est pas conditionné à un plan (l'appairage l'est) |
| **Navigation / routing** | **Oui** | Le dialogue d'appairage gagne un choix de format. Chemins à revérifier : création de projet local → appairage → connexion, et réouverture de l'appairage depuis un projet en attente. |

---

## Plan de test

### Tests unitaires

- [ ] Le contrôleur sert le ZIP quand le fichier existe.
- [ ] 404 `runner_package_unavailable` quand le chemin est vide, absent ou illisible.
- [ ] Le nom de fichier de la réponse est `claude-runner-windows-x64.zip`.

### Tests d'intégration

- [ ] `GET /runner/download/windows` est accessible **sans authentification**.
- [ ] `GET /runner/download` reste inchangé (non-régression).

### Tests frontend

- [ ] Le paquet est proposé en premier quand il est disponible.
- [ ] Un format indisponible n'est pas affiché.
- [ ] La commande affichée suit le format choisi.

### Isolation workspace

- [x] Non applicable — aucune donnée utilisateur.

---

## Notes et décisions

**D1 — Une route par format, pas un paramètre.** `/download?format=windows` rendrait le cache et les
liens directs ambigus, et un paramètre inconnu devrait être arbitré. Deux routes disent ce qu'elles
servent.

**D2 — Un code d'erreur distinct.** `runner_jar_unavailable` et `runner_package_unavailable` ne
décrivent pas le même incident d'exploitation : l'un dit que le jar manque, l'autre que le ZIP n'a
pas été empaqueté. Les confondre ferait chercher au mauvais endroit.

**D3 — L'écran masque plutôt qu'il n'offre un lien mort.** Une gateway déployée avant F-44 n'a pas
le paquet ; proposer un bouton qui répond 404 serait pire que de ne rien proposer.

**D4 — Le paquet est proposé en premier, mais le jar reste visible.** Le paquet sert le cas
d'entreprise ; le jar reste le meilleur format pour un développeur qui a déjà Java. Masquer le jar
ferait télécharger 39 Mo à quelqu'un qui a besoin de 2,5.
