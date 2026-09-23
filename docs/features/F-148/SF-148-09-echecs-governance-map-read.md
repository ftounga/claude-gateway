# Mini-spec — F-148 / SF-148-09 — Root-cause + correction des 25 % d'échec de `governance_map_read`

## Identifiant

`F-148 / SF-148-09`

## Feature parente

`F-148` — Performance du raisonnement (affinages)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-148-09-echecs-governance-map-read`

---

## Objectif

> En une phrase : supprimer les lectures gâchées de `governance_map_read` (gateway-side) en n'énumérant
> plus, dans le contrôle des liens morts de la carte, que de **vrais fichiers de carte** — en excluant
> les répertoires et les noms de fichiers génériques inexistants à la racine.

---

## Root-cause (confirmée dans le code, hypothèse falsifiée)

Sur 7 jours de prod, `governance_map_read` échoue à ~25 % (214/839) : `not_found` (382) et
`is_directory` (258). Les producteurs de `governance_map_read` côté gateway sont :

- `HostMapStore.refresh` (`filesOf`), `IntegriteInspection.passeCarte`, `JugeMatiereReader.lireCarte`,
  `GovernanceMapReadingService.describe/readFile` : tous lisent `GovernanceMapDestinations.filesOf`,
  c.-à-d. des **chemins de fichiers de carte déclarés par les paquets, normalisés** → chemins valides,
  pas la source des échecs.
- `IntegriteInspection.passeLiensMorts` (`IntegriteInspection.java:374,382`) lit, via
  `hostFiles.presence(...)`, les **références libres extraites du contenu des cartes** par
  `MapReferences.of(...)`. **C'est l'unique producteur qui lit du texte non validé.**

Les DEUX causes viennent donc de `MapReferences` :

1. **`is_directory` (258)** — `MapReferences` émet des références de **répertoire** : sujets cités avec
   un slash final (`lzi/`, `data-platform/`, `socle-reseau-corp/`, `poste-aws/`, `lecture-teams/`,
   `repos/` → `nettoie` retire le `/` final), et dépôts (`corporate-center/corp.git`). Lues comme des
   fichiers → le runner répond `is_directory`. (Un répertoire ne peut de toute façon pas être validé
   par une lecture de fichier : présent → `is_directory`, effacé → `not_found`.)
2. **`not_found` (382)** — `MapReferences` émet des **noms de fichiers de carte génériques** cités en
   prose (`STATE.md`, `PLAN-ACTION.md`, et le dégénéré `.md`). Ces fichiers vivent **dans un sujet**
   (`lzi/PLAN-ACTION.md` réussit), **jamais à la racine** du poste → lus à la racine, `not_found`.
   En prime, ce `not_found` fabrique un **faux constat de lien mort**.

Falsification : les autres lecteurs (`destinations.filesOf`) n'utilisent pas de texte libre ; seuls
`passeLiensMorts`/`MapReferences` produisent les cibles fautives listées par l'audit.

---

## Comportement attendu

### Cas nominal

`MapReferences.of(contenu, fichiersDeLaCarte)` ne retient une référence que si sa **dernière
composante** est un **vrai fichier `.md`** (radical non vide + `.md`) — donc :
- un chemin de sujet vers un fichier de carte est retenu : `lzi/PLAN-ACTION.md`, `vieux-sujet/STATE.md` ;
- un répertoire est **écarté** : `lzi/`, `data-platform/`, `repos/portail-client`, `corporate-center/corp.git` ;
- un `.md` **générique de sujet** cité **nu** (sans dossier) est écarté : `STATE.md`, `PLAN-ACTION.md` ;
- le dégénéré `.md` est écarté.

`passeLiensMorts` ne fait donc plus aucune lecture (`governance_map_read`) sur un répertoire ni sur un
nom générique inexistant à la racine ; il continue de lire — et de signaler comme lien mort si absent —
les vrais fichiers de carte référencés (`vieux-sujet/STATE.md`).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Contenu de carte `null`/vide | Aucune référence, aucune exception (inchangé) |
| Référence répertoire (`sujet/`, `repos/depot`, `corp.git`) | Écartée, jamais lue |
| Nom générique nu (`STATE.md`, `PLAN-ACTION.md`, `.md`) à la racine | Écarté, jamais lu, jamais de faux lien mort |
| Vrai fichier de sujet référencé mais absent | Lu, signalé comme lien mort (comportement légitime préservé) |
| Machine muette pendant `passeLiensMorts` | Inchangé (budget/`UNREACHABLE` gérés en amont) |

---

## Critères d'acceptation

- [ ] Une référence de répertoire (slash final, ou dernière composante sans `.md`, ou `.git`) n'est
      jamais retournée par `MapReferences.of` → plus aucune lecture `is_directory`.
- [ ] Un nom générique de fichier de sujet cité **nu** (`STATE.md`, `PLAN-ACTION.md`) n'est jamais
      retourné ; `.md` seul non plus → plus aucune lecture `not_found` fabriquée à la racine.
- [ ] Une référence de fichier de carte **valide** (`sujet/STATE.md`, `sujet/PLAN-ACTION.md`,
      `bastions/bst-01.md`) reste retournée → aucune lecture légitime perdue.
- [ ] `passeLiensMorts` ne lit (`hostFiles.presence`) que des références retenues ; un répertoire cité
      n'entraîne aucun appel.
- [ ] Isolation `user_id` + `host_id` inchangée sur toute lecture (aucune signature touchée).
- [ ] Tous les tests existants de `MapReferences`/`IntegriteInspection` restent verts (non-régression).

---

## Périmètre

### Hors scope (explicite)

- Détection de lien mort vers un **répertoire** effacé : abandonnée volontairement (un répertoire ne
  se valide pas par une lecture de fichier ; c'était précisément la source du bruit `is_directory`).
- Toute modification des autres producteurs (`HostMapStore`, `JugeMatiereReader`,
  `GovernanceMapReadingService`) : ils lisent des chemins déclarés valides, hors sujet.
- La discipline « lire avant d'agir » (F-119) : intacte — on retire des lectures **gâchées**, aucune
  lecture utile.
- Aucun changement d'infra, aucun composant cluster, aucune migration.

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| Référence retenue | Dernière composante = radical non vide + `.md` (insensible à la casse) |
| Exceptions ajoutées | Noms nus `state.md`, `plan-action.md` (formes de sujet citées en prose) |
| Normalisation | Inchangée (`nettoie` : ponctuation de fin, `./` de tête, `/` final) |

---

## Technique

### Composants impactés

| Composant | Opération |
|-----------|-----------|
| `MapReferences` (`integrite`) | Filtre « vrai fichier de carte » + 2 exceptions nues (le seul changement de comportement) |
| `IntegriteInspectionTest` | Renforcé : un répertoire cité n'est pas lu |
| `MapReferencesTest` | Nouveaux cas SF-148-09 |

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun (pas d'UI).

---

## Plan de test

### Tests unitaires

- [ ] `MapReferencesTest` — un répertoire cité (`sujet/`) n'est pas retenu.
- [ ] `MapReferencesTest` — un dépôt (`corporate-center/corp.git`, `repos/portail-client`) n'est pas retenu.
- [ ] `MapReferencesTest` — `STATE.md`/`PLAN-ACTION.md` nus et `.md` seul ne sont pas retenus.
- [ ] `MapReferencesTest` — `sujet/STATE.md` et `sujet/PLAN-ACTION.md` restent retenus (non-régression).
- [ ] `IntegriteInspectionTest` — une référence de répertoire n'entraîne aucun `hostFiles.presence`,
      un vrai fichier de sujet absent reste un lien mort.

### Tests d'intégration

- Non applicable : `MapReferences` est une classe **pure** (aucune E/S) et `passeLiensMorts` est couvert
  par les tests unitaires mockés d'`IntegriteInspection`. Aucun endpoint.

### Isolation workspace / tenant

- [x] Applicable mais **inchangée** : aucune signature `user_id`/`host_id` modifiée ; le filtrage est en
      amont de toute lecture. Vérifié par revue (aucune régression d'isolation possible).

---

## Dépendances

### Subfeatures bloquantes

- Aucune.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **Cache de prompt** : non touché — `MapReferences` ne participe pas à `buildSystemPrompt`.
- **Préoccupation transversale « Contexte tenant »** : non déclenchée (aucun nouveau moyen de résoudre
  le tenant ; signatures inchangées).
- Le fix est placé dans l'**énumération** (`MapReferences`), locus désigné par le cadrage
  (« n'énumérer/lire QUE des fichiers de carte réels »), et bénéficie aussi à `passeCarteNonDeclaree`.
