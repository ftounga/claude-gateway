# Mini-spec — F-38 / SF-38-21 — Un explorateur qui montre le projet, pas ses dépendances

## Identifiant

`F-38 / SF-38-21`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`livrée` — PR #251, mergée le 2026-09-06

> **Écart de procédure, tracé plutôt que masqué.** Cette mini-spec est écrite **après** la
> livraison, pendant le commit de clôture documentaire du lot SF-38-18→21. La séquence CLAUDE.md
> demande la mini-spec **avant** le code ; SF-38-21 est partie directement du banc d'essai, sur un
> défaut constaté à l'écran. Le document est reconstitué à partir du code livré (`ExclusionRules`,
> `RunnerWorkspaceBrowser`) et du message de la PR — il décrit ce qui **est** dans `main`, pas une
> intention. Les trois autres subfeatures du lot (SF-38-18, 19, 20) ont bien leur mini-spec
> antérieure au code.

## Date de création

2026-09-06 (a posteriori)

## Branche Git

`feat/SF-38-21-explorateur-filtre`

---

## Objectif

> Faire que l'explorateur d'un projet runner montre **les fichiers du projet** — et non les
> dépendances installées — et que, s'il en manque quand même, il **le dise** au lieu d'afficher un
> projet amputé en silence.

---

## Déclencheur

Banc d'essai (`docs/features/F-38/BANC-ESSAI-RUNNER.md`), signalement du product owner : *« sur
l'explorateur je ne vois pas tout, le dossier backend est absent »*.

Mesure faite sur la machine : **40 590 fichiers**, dont **40 112** dans `frontend/node_modules`. La
liste traverse les deux bornes du listage — `LIST_MAX_ENTRIES = 20 000` entrées, puis
`MAX_CONTENT_BYTES = 512 Kio` — et ce qui remontait à l'écran était **4 829 lignes de dépendances**
au lieu des **478 fichiers** du projet. Le dossier `backend`, trié après `frontend`, tombait
au-delà de la coupe.

Deux défauts distincts, donc, et il fallait les traiter tous les deux : la liste était **polluée**,
et sa **troncature était muette**.

---

## Comportement attendu

### 1. Le bruit de construction est écarté par défaut

Vingt motifs — dépendances installées, artefacts de compilation, caches d'outillage — sont exclus
du listage **sans qu'aucun fichier de règles n'existe** :

```
node_modules/  target/  build/  dist/  out/
.angular/  .next/  .nuxt/  .svelte-kit/  .parcel-cache/
.gradle/  .venv/  venv/  __pycache__/  .pytest_cache/
.mypy_cache/  .tox/  vendor/  coverage/  .terraform/
```

**Négociables**, contrairement à la liste de secrets `DEFAULT_DENY` (`.env`, `*.pem`, `id_rsa*`,
`.aws/`, `.kube/config`, `.ssh/`) : ils sont évalués **avant** les règles utilisateur, si bien
qu'une négation explicite (`!node_modules/` dans `.runnerignore`) les annule. On écarte du bruit,
on ne protège pas un secret — le contournement doit rester possible pour qui sait ce qu'il fait.

Ils sont tenus dans une **liste séparée** de celle de l'utilisateur : sans quoi le compteur annoncé
au démarrage (« N règles issues de `.runnerignore` ») en annoncerait vingt de trop, et mentirait.

### 2. La troncature se dit

Quand le listage est coupé (`truncated`), l'arborescence porte une ligne supplémentaire :

```
⚠ liste incomplète — trop de fichiers ; ajoutez un .runnerignore et relancez le runner
```

Elle est rendue **comme un chemin**, ce qui n'exige aucun changement de contrat : l'écran l'affiche
là où il affiche les fichiers. Mieux vaut une ligne qui dérange qu'un projet amputé en silence.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun `.runnerignore` ni `.gitignore` | Le bruit est écarté quand même ; source annoncée `(aucun)` |
| `!node_modules/` dans `.runnerignore` | Les dépendances redeviennent visibles — choix assumé |
| Négation portant sur un secret (`!.env`) | **Sans effet** : `DEFAULT_DENY` n'est jamais négociable |
| Listage toujours tronqué malgré les exclusions | La ligne d'avertissement est ajoutée en fin de liste |
| Listage complet | Aucun marqueur ajouté |
| Dossier vide | Liste vide — état normal, point de départ d'un projet neuf |

---

## Critères d'acceptation

- [x] Sans aucun fichier de règles, `node_modules/`, `target/`, `build/`… sont écartés du listage.
- [x] Une négation explicite dans `.runnerignore` annule un motif de bruit.
- [x] Une négation **n'atteint jamais** un motif de `DEFAULT_DENY` — un secret reste exclu.
- [x] Le compteur de règles annoncé au démarrage compte les règles **du fichier**, pas le bruit.
- [x] Un listage tronqué porte la ligne d'avertissement ; un listage complet n'en porte pas.
- [x] Isolation `user_id` inchangée : le parcours passe par `RunnerWorkspaceBrowser`, qui exige le
      workspace possédé avant tout appel d'outil, et journalise dans `runner_audit`.

---

## Périmètre

### Hors scope

- **Le rechargement à chaud du `.runnerignore`**, un temps envisagé : retiré. Les règles sont lues à
  la construction de la pile d'outils ; les recharger à chaud demanderait d'invalider un `PathGuard`
  partagé par tous les outils — pour un confort dont le besoin disparaît largement avec les
  exclusions par défaut, et que le message de troncature couvre en disant « relancez le runner ».
- Une **pagination** de l'arborescence : les bornes du listage restent celles du contrat d'outil.
- Un **écran de réglage** des exclusions : le fichier `.runnerignore` reste le seul point d'entrée.

---

## Technique

### Endpoint(s)

Aucun nouveau. `GET /api/workspaces/{id}/files` (explorateur runner, SF-38-17) rend une entrée de
plus quand la liste est coupée.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `runner/ExclusionRules` (runner) | `DEFAULT_NOISE` (20 motifs), liste `noiseRules` séparée, évaluée avant les règles utilisateur |
| `runner/browse/RunnerWorkspaceBrowser` (backend) | `TRUNCATED_MARKER` ajouté à la liste quand `result.truncated()` |

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| **Contexte tenant** | **Oui** | Aucun changement de mécanique : `RunnerWorkspaceBrowser` continue d'exiger le workspace possédé (`user_id`) avant d'adresser le moindre appel au runner, et de consigner dans `runner_audit`. Ce lot ne touche qu'au **contenu** de la liste rendue. |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |
| **Sécurité** | **Oui** | La distinction bruit / secret est le cœur du lot : `DEFAULT_NOISE` est négociable, `DEFAULT_DENY` ne l'est pas. Un test verrouille qu'une négation ne peut pas exposer `.env`. |

---

## Plan de test

### Tests unitaires — runner (`ExclusionRulesTest`)

- [x] `ecarteLeBruitDeConstructionSansAucuneRegle`
- [x] `uneNegationExpliciteAnnuleLeBruitParDefaut`
- [x] `leBruitNeProtegeJamaisUnSecret`

### Tests unitaires — backend (`RunnerWorkspaceBrowserTest`)

- [x] `saysWhenTheListingIsIncompleteRatherThanShowingAnAmputedProject`
- [x] `doesNotAddTheMarkerOnACompleteListing`

### Isolation workspace

- [x] Applicable — couverte par les tests existants de `RunnerWorkspaceBrowser` (SF-38-17).

### Résultat à la livraison

**146 tests runner** (+3), **1 350 tests backend** (+2), tous verts.

---

## Notes et décisions

**D1 — Écarter par défaut plutôt que demander un `.runnerignore`.** Exiger un fichier de règles
aurait fait porter à l'utilisateur la connaissance d'un mode d'échec qu'il ne pouvait pas
diagnostiquer : il voyait un dossier manquant, pas une liste coupée.

**D2 — Le bruit est négociable, le secret ne l'est pas.** Les deux listes existent séparément et ne
sont pas évaluées au même rang. C'est ce qui permet à `!node_modules/` de fonctionner sans ouvrir la
moindre porte sur `.env`.

**D3 — Deux listes distinctes pour que le compteur ne mente pas.** Fondre le bruit dans les règles
utilisateur aurait fait annoncer « 23 règles issues de .runnerignore » à qui en a écrit trois.

**D4 — La troncature se dit, même si la ligne dérange.** Afficher un projet incomplet en silence a
coûté dix minutes de recherche d'un dossier que le système savait ne pas avoir envoyé — c'est
exactement le mode d'échec que tout ce chantier cherche à supprimer.

**D5 — Le marqueur voyage comme un chemin.** Aucun changement de contrat d'API, aucun changement
d'écran : l'information arrive là où l'utilisateur regarde déjà.
