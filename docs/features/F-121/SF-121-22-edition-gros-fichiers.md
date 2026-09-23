# Mini-spec — F-121 / SF-121-22 — Édition/écriture de gros fichiers (>512 Kio)

## Identifiant

`F-121 / SF-121-22`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`in-progress`

## Date de création

2026-09-23

## Branche Git

`feat/SF-121-22-edition-gros-fichiers`

---

## Objectif

> En une phrase : permettre à `edit_file` sur la machine (cible RUNNER) de modifier un fichier plus
> gros que la borne du champ `content` (512 Kio) sans le tronquer ni le corrompre, en gardant la
> sémantique « remplacement exact unique, échec si ambigu » et **sans changer le protocole runner**.

---

## Comportement attendu

### Contexte du défaut (écart de parité)

`editFileOnRunner` (`AtelierChatService`) compose l'édition ciblée des primitives runner :
`read_file` → `AtelierFileText.replace` → `write_file`. Or :

- `read_file` (runner `FileTools.readFile`) **tronque** le contenu à `MAX_CONTENT_BYTES` (512 Kio) ;
- `write_file` (gateway `RunnerToolGateway.MAX_WRITE_BYTES` = 512 Kio) **refuse** au-delà.

Résultat aujourd'hui : dès qu'un fichier dépasse 512 Kio, la lecture est tronquée et `editFileOnRunner`
**refuse** l'édition (`INVALID_INPUT`, « lecture tronquée ») — décision de sûreté D2 de SF-39-06 : éditer
un fragment puis le réécrire détruirait la fin du fichier. Conséquence : **impossible d'éditer un vrai
fichier de code** de plus de 512 Kio sur le poste, alors que Claude Code le fait.

### Cas nominal

1. Le modèle appelle `edit_file` avec `path`, `old_string`, `new_string`, `replace_all?`.
2. `editFileOnRunner` lit d'abord par `read_file`.
   - **Fichier ≤ 512 Kio** (lecture non tronquée) : chemin inchangé (lire → remplacer → `write_file`).
   - **Fichier > 512 Kio** (lecture tronquée, `read.truncated()`) : on relit **l'intégralité** du
     fichier par **tranches binaires** via `read_file_bytes` (déjà au contrat runner depuis F-110 /
     SF-110-03), on reconstitue les octets, on décode en UTF-8.
3. On applique `AtelierFileText.replace(contenuComplet, old, new, replaceAll)` — **sémantique
   inchangée** : remplacement exact, échec si le texte est absent, échec si présent plusieurs fois
   sans `replace_all`.
4. On réécrit :
   - contenu résultant ≤ 512 Kio → `write_file` (chemin historique) ;
   - contenu > 512 Kio → réécriture par **tranches binaires** via `write_file_bytes` (déjà au contrat
     runner depuis F-115 / SF-115-01 ; tranche à `offset 0` tronque et crée, les suivantes s'ajoutent).
5. Le message rendu au modèle reste `Fichier modifié : <path> (<n> remplacement[s])`.

**Borne haute de sûreté préservée** : `read_file` refuse déjà `> 8 Mio` (`MAX_READ_BYTES`) en
`too_large` **avant** troncature ; l'édition héritant de ce refus, la plage éditable passe de
« ≤ 512 Kio » à « ≤ 8 Mio », jamais au-delà. Aucun contenu de fichier ne transite par le champ
`content` d'un bloc (512 Kio) : les octets passent en tranches Base64 bornées à `MAX_BYTES_CHUNK`
(384 Kio), sous la trame de 1 Mio.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `old_string` absent | `INVALID_INPUT`, message `AtelierFileText` (inchangé) |
| Texte introuvable après relecture complète | `INVALID_INPUT`, « Texte introuvable… » (inchangé) |
| Texte présent plusieurs fois sans `replace_all` | `INVALID_INPUT`, « Texte trouvé N fois… » (inchangé) |
| Fichier > 8 Mio | `too_large` de `read_file` propagé (aucune édition, aucune corruption) |
| Runner **antérieur** à F-110/F-115 (pas de `read_file_bytes`/`write_file_bytes`) | Issue `unsupported_tool` du runner propagée telle quelle : **échec propre, jamais de troncature ni de corruption** |
| Échec de transport (timeout/indispo) pendant une tranche | Issue d'erreur propagée ; « non concluant » côté rendu (SF-121-04), aucune écriture partielle réputée réussie |

---

## Critères d'acceptation

- [ ] Un `edit_file` sur un fichier > 512 Kio et ≤ 8 Mio (lecture tronquée) **réussit** : le fichier
      est relu intégralement par `read_file_bytes`, le remplacement appliqué, réécrit par
      `write_file_bytes` ; message `Fichier modifié : … (N remplacement…)`.
- [ ] Un `edit_file` sur un fichier ≤ 512 Kio garde **exactement** le chemin historique
      (`read_file` → `write_file`), aucun appel `read_file_bytes`/`write_file_bytes`.
- [ ] La sémantique « remplacement exact unique, échec si ambigu » est **préservée** sur le gros
      fichier : `old_string` introuvable → erreur ; présent plusieurs fois sans `replace_all` → erreur.
- [ ] Un contenu résultant > 512 Kio est écrit **par tranches** (`write_file_bytes`), la 1re tranche
      à `offset 0` (tronque/crée), sans jamais franchir le champ `content` de 512 Kio.
- [ ] Un runner qui ne supporte pas `read_file_bytes` renvoie `unsupported_tool` → l'édition **échoue
      proprement** (aucun `write_file`/`write_file_bytes` avec un contenu tronqué).
- [ ] **Aucune évolution du protocole runner** : les outils employés (`read_file`, `read_file_bytes`,
      `write_file`, `write_file_bytes`) existent déjà au contrat ; le runner déployé n'a **rien** à
      mettre à jour (si assez récent, ≥ F-115).
- [ ] Cache de prompt F-134 préservé (aucun ajout au préfixe système), F-119 intacte, Provider
      Independence respectée (aucune dépendance modèle), Gateway-First (pur transport/plomberie),
      aucun composant cluster.

---

## Périmètre

### Hors scope (explicite)

- **Édition côté SANDBOX/stockage** (`executeToolOnStorage`) : elle lit déjà le fichier **entier**
  (`workspaceService.readFile`, sans troncature 512 Kio) et écrit jusqu'à `properties.maxFileBytes()` ;
  elle n'est pas concernée par la borne `FileTools`. Non touchée.
- **MultiEdit** (F-121-06) : hors sujet.
- **Relever `MAX_CONTENT_BYTES`/`MAX_WRITE_BYTES` du contrat** : non — on **réutilise** les primitives
  binaires par tranches déjà prévues pour les gros transferts, plutôt que d'agrandir la trame.
- **Détection binaire / multimodal** (F-121-15) : `edit_file` reste une opération texte (UTF-8), comme
  aujourd'hui.

---

## Technique

### Endpoint(s)

Aucun. (Réglage interne de la boucle tool-use.)

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable (aucun changement de schéma).

### Composants impactés

| Composant | Opération |
|-----------|-----------|
| `backend/.../atelier/AtelierChatService.java` | `editFileOnRunner` : repli gros fichier (lecture/écriture par tranches) ; 2 helpers privés + 1 record interne |
| `RunnerToolGateway` (`readFileBytes`, `writeFileBytes`) | **utilisés** (déjà existants), non modifiés |
| Runner `FileTools` | **non touché** |

### Préoccupations transversales

- **Auth / Principal** : aucune. L'édition passe par le même `RunnerTarget`/isolation `user_id` que le
  chemin existant ; aucun nouvel accès données.
- **Contexte tenant** : inchangé (même `RunnerTarget`).
- **Plans / limites** : neutre — pas de nouvel appel LLM ; le volume d'octets transite en tranches
  déjà bornées (aucun impact quota, aucun surcoût de tokens).
- **Navigation / routing** : sans objet (pas de frontend).

---

## Plan de test

### Tests unitaires (`AtelierChatServiceRunnerTargetTest`, premier plan)

- [ ] `edit_file` sur fichier > 512 Kio (lecture tronquée) : relecture par `read_file_bytes` (une ou
      plusieurs tranches), remplacement appliqué, réécriture par `write_file_bytes` ; message correct.
- [ ] `edit_file` sur fichier ≤ 512 Kio : chemin historique conservé, **jamais** de `read_file_bytes`
      ni `write_file_bytes` (verify never).
- [ ] Gros fichier, `old_string` introuvable dans le contenu complet reconstitué → erreur
      `Texte introuvable`, aucune écriture.
- [ ] Gros fichier dont le résultat dépasse 512 Kio → écriture par tranches `write_file_bytes`,
      première tranche à `offset 0`.
- [ ] Runner sans `read_file_bytes` (lecture tronquée puis `unsupported_tool`) → erreur propre,
      aucun `write_file`/`write_file_bytes`.
- [ ] (Adapté) L'ancien test « refuse une lecture tronquée » devient « replie sur la lecture par
      tranches » ; on garde un cas de refus propre quand la relecture par tranches échoue.

### Tests d'intégration

- N/A (pas d'endpoint nouveau ; la boucle est couverte par les tests unitaires ci-dessus).

### Isolation workspace/utilisateur

- [x] Applicable — le chemin d'édition passe par `RunnerTarget` (poste + projet) inchangé ;
      aucun nouvel accès aux données, isolation `user_id` héritée du chemin existant. Vérifié par les
      tests de routage RUNNER existants (aucune écriture vers `workspaceService`).

---

## Dépendances

### Subfeatures bloquantes

- `SF-110-03` (read_file_bytes) — done. `SF-115-01` (write_file_bytes) — done.
- `SF-39-06` (édition ciblée) — done. `SF-121-19` (fraîcheur fichiers) — done (garde amont inchangée).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **Décision (voie retenue)** : *réutiliser les primitives binaires par tranches* (`read_file_bytes` /
  `write_file_bytes`) plutôt qu'ajouter un outil `edit_file` **côté runner** (patch en place). Motifs :
  (1) **aucune mise à jour runner** requise — cohérent avec la décision D1 de SF-39-06 (« un runner
  installé n'a rien à mettre à jour ») et toute la lignée F-121 (« aucun runner touché ») ; (2) la
  sémantique exacte de remplacement reste **au même endroit** (`AtelierFileText.replace`), donc une
  seule vérité, testable ; (3) borne haute de sûreté (8 Mio) déjà en place via `read_file`.
- **Mise à jour runner nécessaire : NON.** Les quatre outils employés sont déjà au contrat (≥ F-115).
  Un runner antérieur échoue **proprement** (`unsupported_tool`), jamais de corruption.
- **Sûreté** : on ne réécrit **jamais** à partir d'un contenu tronqué. Le contenu complet est
  reconstitué octet par octet (Base64 sans perte) avant décodage UTF-8, comme le chemin ≤ 512 Kio
  (décode/encode UTF-8), donc **sans régression** de fidélité pour du texte UTF-8 valide.
