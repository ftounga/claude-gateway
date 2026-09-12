# Mini-spec — F-94 / SF-94-01 — La matière et le bloc de verdict

## Identifiant

`F-94 / SF-94-01`

## Feature parente

`F-94` — Le juge indépendant

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-94-01-la-matiere-et-le-bloc-de-verdict`

---

## Objectif

Poser les **deux pièces déterministes** du juge indépendant — **ce qu'on lui donne** (la carte du
poste d'un côté, les notes des projets de l'autre) et **ce qu'on lit de sa réponse** (le bloc
`===VERDICT===`, et lui seul) — pour que le second appel de SF-94-02 n'ait plus qu'à poser la
question.

---

## Le défaut, tel qu'il se constate aujourd'hui

`JugeFinDeTourControl` **ne fait aucun appel**. Il lit un marqueur que le modèle pose lui-même en
terminant son tour :

| Ce qui existe | Ce que le prompt décrit |
|---|---|
| une **auto-déclaration** — le modèle dit ce qu'il n'a pas promu | un **audit** — on regarde la carte et les notes, et on compare |
| un modèle qui oublie de promouvoir **oublie aussi de le déclarer** | un second regard, qui ne dépend pas de la mémoire du premier |

Le motif invoqué était Provider-First. Il ne tient pas ici : on ne réimplémente rien, on **pose une
seconde question** à un modèle — exactement ce que la règle recommande.

Cette subfeature ne pose pas encore la question. Elle livre la matière et la lecture du verdict,
parce que ce sont les deux seules parties **vérifiables sans fournisseur**, et que le bug que le
prompt raconte (« un juge bavard concluant AUCUN était pris pour une alerte ») vit entièrement dans
la lecture du verdict.

---

## Comportement attendu

### 1. Le bloc de verdict — `JugeVerdict`

Le juge **raisonne librement**. Il doit terminer par une ligne contenant exactement
`===VERDICT===`, suivie soit de `AUCUN`, soit de lignes de la forme :

```
- <élément> — cité dans <fichier>
```

**Seul ce qui suit le marqueur est lu.** C'est la règle qui a coûté un bug au prompt d'origine :
quand on testait la sortie entière, un juge bavard qui concluait « aucun élément manquant » en fin
de raisonnement était pris pour une alerte.

| Réponse du juge | Lecture |
|---|---|
| raisonnement… `===VERDICT===` `AUCUN` | **rien à signaler** |
| raisonnement… `===VERDICT===` `- bastion bst-01 — cité dans STATE.md` | **un élément**, avec sa source |
| « aucun élément ne manque » **sans** marqueur | **illisible** → SF-94-03 alerte |
| marqueur présent, **bloc vide** | **illisible** → un blanc n'est pas un silence |
| deux marqueurs (le juge cite la consigne avant de l'appliquer) | **le dernier fait foi** |

**Tolérance de forme, jamais de fond** : le séparateur accepté est `—`, `--` ou ` - ` ; une puce peut
être `-` ou `*` ; `AUCUN` se reconnaît sans accent ni casse. On corrige un modèle sur ce qu'il dit,
jamais sur sa typographie — c'est déjà la règle de `FinDeTourMarker` (F-93).

**Bornes** : 20 éléments au plus, 200 caractères par élément, 120 par source. Au-delà, ce n'est plus
une liste à vérifier.

### 2. La matière — `JugeMatiere` et `JugeMatiereReader`

Deux côtés, et un seul sens de lecture : **qu'est-ce qui est cité dans les notes et absent de la
carte ?**

| Côté | Ce que c'est | D'où ça vient |
|---|---|---|
| **la carte** | les fichiers de carte posés à la **racine du poste** | `GovernanceMapDestinations.filesOf` + `GovernanceHostFiles.read` |
| **les notes** | les `.md` posés à la **racine de chaque projet** du poste | `GovernanceProjectFiles.listPaths` + `read` |

**Les notes sont les `.md` de la racine du projet, jamais des sous-dossiers** (`sources/`,
`context/`, `.claude/`) : des sources brutes sont du bruit, et elles feraient dire au juge tout et
n'importe quoi.

**Un gabarit jamais touché n'est pas une note.** Un fichier de projet dont le contenu est **identique
à celui que le paquet a déposé** est écarté : il ne porte aucun fait, seulement les exemples du
gabarit (`cluster « atlas » (10.0.4.0/24)` figure tel quel dans `STATE.md`), et l'inclure
fabriquerait des alertes fantômes dès le premier projet. La comparaison est faite sur le contenu
réellement déposé par les paquets actifs, jamais sur une liste de noms gravée dans le code.

**Bornes, parce que ça part dans un appel** : 20 notes au plus, 20 000 caractères par fichier,
120 000 caractères au total. Ce qui est coupé se **dit** dans la matière (`tronquee`).

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Poste sans machine (« Hébergé ») | aucune matière — il n'y a pas de racine, donc pas de carte |
| Aucun paquet actif sur le poste | aucune matière — aucun fichier de carte n'est attendu |
| Machine injoignable en lisant la carte | on **arrête** la lecture et on rend « pas de matière » : on ne compare pas à une carte qu'on n'a pas |
| Un fichier de carte illisible (droits) | il est **sauté**, la lecture continue : le suivant peut répondre |
| Projet illisible / effacé | il est sauté ; les autres projets du poste restent lus |
| Aucune note nulle part | aucune matière — il n'y a rien à comparer |
| Marqueur `===VERDICT===` absent | `JugeVerdict.illisible()` — jamais un « rien à signaler » |
| Bloc de verdict vide ou incompréhensible | `JugeVerdict.illisible()` |

**Rien ne lève.** Toutes ces situations rendent une matière vide ou un verdict illisible ; aucune ne
propage d'exception — le juge est branché en fin de tour, et un filet qui casse le tour d'un
utilisateur serait pire que pas de filet.

---

## Critères d'acceptation

- [x] `JugeVerdict.parse` ne lit **que** ce qui suit le dernier `===VERDICT===`
- [x] un juge bavard concluant « AUCUN » **hors** du bloc rend `illisible`, jamais « rien à signaler »
- [x] `AUCUN` dans le bloc rend un verdict **lisible et vide**
- [x] `- <élément> — cité dans <fichier>` rend l'élément **et** sa source
- [x] les trois séparateurs (`—`, `--`, ` - `) et les deux puces (`-`, `*`) sont acceptés
- [x] un bloc vide, absent, ou sans aucune ligne exploitable rend `illisible`
- [x] les bornes (20 éléments, 200/120 caractères) sont appliquées
- [x] `JugeMatiereReader` ne retient que les `.md` de la **racine** d'un projet
- [x] un fichier identique au gabarit déposé est **écarté** des notes
- [x] une carte injoignable rend une matière **inutilisable**, pas une carte vide
- [x] un poste « Hébergé » rend une matière inutilisable sans aucun appel runner
- [x] **isolation** : la matière d'un poste d'autrui n'est jamais lue — le poste arrive
      déjà vérifié possédé (`GovernanceHostScope`), les projets sont lus par `user_id` + `host_id`
- [x] aucune méthode ne lève : projet effacé, runner muet, paquet dépublié

---

## Périmètre

### Hors scope (explicite)

- **Le second appel** au fournisseur — SF-94-02
- **Le branchement en fin de tour**, le déclenchement sur écriture, le message correctif — SF-94-03
- Toute suppression ou modification de `JugeFinDeTourControl` : il reste, il est gratuit, et il
  attrape le cas où le modèle **sait** qu'il n'a pas promu
- Tout écran : le résultat du juge est rendu par le bloc de point de contrôle existant du terminal

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Normalisation |
|---|---|---|---|---|
| `JugeVerdict.Element.element` | Oui | 200 | texte libre non vide | `strip`, puce retirée |
| `JugeVerdict.Element.source` | Non | 120 | nom de fichier tel que cité | `strip`, `cité dans` retiré |
| éléments d'un verdict | — | 20 | — | dédoublonnés (insensible à la casse) |
| notes d'une matière | — | 20 fichiers | `.md` de la racine d'un projet | chemin normalisé |
| contenu d'un fichier | — | 20 000 caractères | — | coupe **dite** |
| matière entière | — | 120 000 caractères | — | coupe **dite** |

---

## Technique

### Endpoint(s)

Aucun. Ce sont des composants internes.

### Tables impactées

Aucune. Lecture seule (paquets, activations) + lecture machine par le runner.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [x] `JugeVerdictTest` — bloc `AUCUN` → lisible et vide
- [x] `JugeVerdictTest` — « AUCUN » hors du bloc → illisible (**le bug du prompt**)
- [x] `JugeVerdictTest` — éléments avec les trois séparateurs et les deux puces
- [x] `JugeVerdictTest` — marqueur absent → illisible
- [x] `JugeVerdictTest` — bloc vide → illisible
- [x] `JugeVerdictTest` — deux marqueurs → le dernier fait foi
- [x] `JugeVerdictTest` — bornes en nombre et en longueur, doublons écartés
- [x] `JugeMatiereReaderTest` — sous-dossiers écartés, `.md` de racine retenus
- [x] `JugeMatiereReaderTest` — gabarit non modifié écarté, gabarit enrichi retenu
- [x] `JugeMatiereReaderTest` — carte injoignable → matière inutilisable
- [x] `JugeMatiereReaderTest` — poste « Hébergé » → aucune lecture, matière inutilisable
- [x] `JugeMatiereReaderTest` — bornes de volume, coupe signalée

### Tests d'intégration

Sans objet : aucun endpoint. Le chemin complet est couvert par SF-94-03.

### Isolation workspace

- [x] Applicable — `JugeMatiereReaderTest` vérifie que les projets viennent de
      `GovernanceHostScope.projectsOf(userId, host)` et d'aucune autre source.

---

## Dépendances

### Subfeatures bloquantes

- `SF-92-01` — les fichiers de carte à la racine — **done**
- `SF-92-02` — lire la carte du poste — **done**
- `SF-93-01` — la promotion dit où (`GovernanceMapDestinations`) — **done**

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

| Préoccupation | Touchée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | aucun changement d'authentification |
| Contexte tenant | **Oui (lecture)** | `GovernanceHostScope` (déjà livré, non modifié) : le poste arrive vérifié possédé, les projets sont lus par `user_id` + `host_id`. Aucun nouveau chemin de résolution du tenant n'est créé. |
| Plans / limites | Non | aucun quota lu ni posé ici (SF-94-02 enregistre la consommation) |
| Navigation / routing | Non | aucun écran |

---

## Notes et décisions

**D1 — Le dernier marqueur fait foi.** Une réponse peut citer la forme attendue avant de la poser
(en expliquant la consigne). Même arbitrage que `FinDeTourMarker`, pour la même raison.

**D2 — Un bloc vide est illisible, pas un silence.** C'est la traduction directe de la règle de la
journée : *le filet doit échouer bruyamment*. Conclure « rien à signaler » d'un blanc, c'est
exactement le bug que le prompt raconte, à l'envers.

**D3 — Un gabarit non modifié est écarté des notes.** Les gabarits portent des exemples
(`cluster « atlas »`, `10.0.4.0/24`) qui feraient alerter le juge dès le premier projet. La
comparaison se fait sur le contenu **réellement déposé** par les paquets actifs : une liste de noms
gravée dans le code mentirait au premier gabarit ajouté.

**D4 — Une carte injoignable ne donne pas une carte vide.** Comparer des notes à une carte qu'on n'a
pas su lire ferait signaler *tout* ce qu'elles contiennent. La matière est alors inutilisable, et
SF-94-03 ne pose aucune question.
