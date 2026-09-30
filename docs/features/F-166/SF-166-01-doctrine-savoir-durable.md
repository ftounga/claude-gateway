# Mini-spec — F-166 / SF-166-01 — La doctrine « savoir durable »

## Identifiant

`F-166 / SF-166-01`

## Feature parente

`F-166` — Savoir durable (REPO-MAP.md / STATE.md entretenus)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-166-01-doctrine-savoir-durable`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Injecter dans le prompt système du terminal (host + sujet) une **doctrine « savoir durable »** stable
qui apprend à l'agent à **lire d'abord** `REPO-MAP.md` / `STATE.md` et à **proposer de les entretenir**
aux moments clés, au lieu de re-scanner et re-dériver le dépôt à chaque tour.

---

## Comportement attendu

### Cas nominal

1. Un tour s'ouvre sur un workspace du **poste** (`isRunnerTarget()` : terminal du poste **ou** terminal
   de sujet). `buildSystemPrompt` assemble le préfixe.
2. Le nouveau littéral `DURABLE_KNOWLEDGE_DOCTRINE` est **ajouté** au préfixe (bloc additif), à la suite
   des doctrines de carte existantes (`DESTINATION_ANNOUNCE_DOCTRINE`), avant le bloc host-terminal-only.
3. La doctrine énonce, en littéral stable : lire l'artefact existant d'abord ; s'il est absent, proposer
   d'en créer un aux moments clés (première exploration, décision importante, avant passation), pas à
   chaque tour ; construire de façon bornée (`git ls-files` + points d'entrée, pas de relecture complète) ;
   faire porter à l'artefact une ligne « à revérifier avant de s'y fier » ; traiter la carte comme un
   pointeur à revérifier, jamais un substitut à la lecture du fichier réel quand la précision compte ;
   rafraîchir quand le repo bouge ; ne rien retirer du contexte utile.
4. Le reste du préfixe (rôle, environnement, doctrines existantes, CLAUDE.md, état du sujet, skills,
   outils) est **inchangé à l'octet près** hormis l'ajout du bloc → cache F-134 préservé.

### Cas d'erreur

> Cette subfeature n'ajoute ni endpoint ni I/O : les « cas d'erreur » sont des cas de conduite couverts
> par le littéral, plus la robustesse de scope.

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Carte présente mais **périmée** (repo a bougé) | La doctrine impose de la traiter comme pointeur **à revérifier** et de la **rafraîchir** ; ne jamais s'y fier pour un nom exact / une écriture de code sans rouvrir le fichier réel | N/A |
| Workspace **hors scope** (SANDBOX hébergé, terminal Teams) | La doctrine est **absente** du préfixe (préfixe plus court, cache préservé) | N/A |
| Aucun outil d'écriture / repo vide | La doctrine reste une consigne de conduite (aucune I/O déclenchée par l'injection) ; la proposition ne s'active que quand un artefact aurait du sens | N/A |

---

## Critères d'acceptation

> Chaque critère est vérifiable en test unitaire sur `buildSystemPrompt`.

- [ ] La doctrine est **présente** dans le préfixe d'un **terminal de poste** (`isHostTerminal()`).
- [ ] La doctrine est **présente** dans le préfixe d'un **terminal de sujet / projet RUNNER**
      (`isRunnerTarget()` sans `isHostTerminal()`).
- [ ] La doctrine est **absente** du préfixe d'un **projet hébergé SANDBOX** (hors scope).
- [ ] Le littéral énonce sans ambiguïté : lire l'artefact d'abord ; proposer aux moments clés, pas à
      chaque tour ; construction bornée (`git ls-files` + points d'entrée) ; ligne « à revérifier avant
      de s'y fier » ; carte = pointeur à revérifier, jamais substitut à la lecture réelle ; rafraîchir
      quand le repo bouge ; additive (ne retire rien).
- [ ] **Non-régression cache / stabilité** : le préfixe reste **byte-stable entre deux constructions
      identiques** ; les doctrines universelles existantes (F-125, SF-141-01, etc.) restent présentes et
      inchangées à côté de l'ajout.
- [ ] **Isolation inchangée** : aucun accès aux données, aucun filtre `user_id` touché (l'injection ne
      lit ni n'écrit de donnée applicative).

---

## Périmètre

### Hors scope (explicite)

- SF-166-02 (déclencheur léger après N lectures sans artefact) — **réserve, différé**.
- Tout **nouvel outil**, endpoint, table ou migration.
- Toute **écriture automatique** d'un artefact sans proposition.
- Tout **index persistant en base / embeddings dédiés** (sémantique = F-162 recall).
- Workspaces **SANDBOX** (hébergé) et terminaux **Teams**.
- Rendu / composant **frontend** (cette subfeature est prompt-only, backend).

---

## Valeurs initiales

Sans objet — aucune entité créée, aucun état de ressource modifié.

---

## Contraintes de validation

Sans objet — aucun champ utilisateur, aucune entrée validée. Le seul « champ » est un **littéral
constant** compilé (`private static final String DURABLE_KNOWLEDGE_DOCTRINE`), stable par construction.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular (si applicable)

Aucun.

### Fichiers impactés

| Fichier | Opération |
|---------|-----------|
| `backend/src/main/java/fr/claudegateway/atelier/AtelierChatService.java` | Ajout du littéral `DURABLE_KNOWLEDGE_DOCTRINE` + son injection scopée dans `buildSystemPrompt` |
| `backend/src/test/java/fr/claudegateway/atelier/AtelierChatServiceSystemPromptTest.java` | Tests : présence (host + sujet), absence (SANDBOX), contenu, non-régression |

---

## Préoccupation transversale — « prompt système partagé » (COCHÉE)

> Déclencheur obligatoire : cette subfeature modifie `buildSystemPrompt`, préfixe **partagé** par toutes
> les cibles d'injection. Analyse d'impact requise (sinon BLOCAGE CLAUDE.md).

**Cibles d'injection de `buildSystemPrompt` (préfixe système) recensées :**

| Cible d'injection | Nature | Impact de l'ajout |
|-------------------|--------|-------------------|
| Amorce de rôle (RUNNER vs hébergé) | universelle, selon cible | **Inchangée** |
| `environmentBlock` | universelle | **Inchangée** |
| `SECTION_METHOD` + `INVESTIGATION_DISCIPLINE` | universelle | **Inchangée** |
| `RESTRAINT_DOCTRINE` | universelle | **Inchangée** |
| `SECTION_STYLE` + `RESPONSE_STYLE` | universelle | **Inchangée** |
| `CARD_SILENCE_DOCTRINE` | universelle | **Inchangée** |
| `ESSENTIAL_ANSWER_DOCTRINE` | universelle | **Inchangée** |
| `ADVICE_DECISION_DOCTRINE` | universelle | **Inchangée** |
| `ASK_QUESTION_DOCTRINE` | universelle | **Inchangée** |
| `DESTINATION_ANNOUNCE_DOCTRINE` | universelle | **Inchangée** (l'ajout se place juste après) |
| **`DURABLE_KNOWLEDGE_DOCTRINE`** (NOUVEAU) | **host + sujet** (`isRunnerTarget()`) | **AJOUT additif** |
| `SUBJECT_ROUTING_DOCTRINE` / `SUBJECT_HANDOFF_DOCTRINE` | host-terminal-only | **Inchangées** |
| `ANSWER_PLAN_DIRECTIVE` (mode) | conditionnel mode | **Inchangée** |
| `SECTION_TOOLS` + `toolChoiceSection` | universelle | **Inchangée** |
| Notices de volets (Teams / Radar / pages / présentations / images…) | conditionnelles | **Inchangées** |
| Préambule + CLAUDE.md projeté, règles de gouvernance | conditionnels | **Inchangés** |
| État courant du sujet (`SUBJECT_STATE_*`, F-148) | sujet | **Inchangé** |
| Catalogue de skills, arbre | universel | **Inchangé** |

**Confirmation :**

- L'ajout est **strictement additif** : aucune cible existante n'est retirée, réordonnée ni modifiée.
- Le nouveau bloc est un **littéral stable** (`private static final String`) — le point d'insertion et
  le contenu sont constants → **cache de prompt F-134 préservé** (aucune volatilité de préfixe
  introduite).
- Le scope `isRunnerTarget()` (host + sujet) est **cohérent** avec le placement des autres doctrines
  scopées ; hors scope (SANDBOX, Teams), le préfixe reste **plus court** (cache préservé).

---

## Plan de test

### Tests unitaires (`AtelierChatServiceSystemPromptTest`, `buildSystemPrompt` via `systemPrompt()`)

- [ ] `durableKnowledgeDoctrinePresentOnTheHostTerminal` — présente sur terminal du poste, + son contenu
      clé (lecture d'abord, proposer aux moments clés, construction bornée, à revérifier, pointeur non
      substitut, additive).
- [ ] `durableKnowledgeDoctrinePresentOnARunnerProject` — présente sur terminal de sujet / projet RUNNER.
- [ ] `durableKnowledgeDoctrineAbsentOnASandboxProject` — absente sur projet hébergé SANDBOX.
- [ ] Non-régression : le préfixe reste **byte-stable entre deux constructions** (réutilisation du test
      de stabilité existant, ou assertion `isEqualTo` sur deux appels) ; les doctrines universelles
      voisines (`Dis où tu ranges un fait durable`, `Tenue de la carte, en silence`) restent présentes.

### Tests d'intégration

- Sans objet (aucun endpoint). La construction du prompt est déjà couverte par le test unitaire du
  service, qui observe la consigne réellement envoyée au fournisseur.

### Isolation workspace / utilisateur

- [x] Non applicable — raison : l'ajout est une **constante de préfixe** ; il ne lit ni n'écrit aucune
      donnée, ne touche aucun filtre `user_id`. Le scope dépend uniquement du type de workspace du tour
      courant, déjà résolu et isolé en amont (`requireOwned`).

---

## Dépendances

### Subfeatures bloquantes

- Aucune (le patron des doctrines SF-141 est déjà sur `main`).

### Questions ouvertes impactées

- Aucune. (Le sémantique persistant est explicitement laissé à F-162 ; pas d'OQ rouverte.)

---

## Notes et décisions

- **Décision de scope (par défaut, flaguée)** : « host + sujet » = `isRunnerTarget()`. Ce prédicat couvre
  le terminal du poste (racine) **et** les terminaux de sujet / projet sur le poste — les seuls endroits
  où vivent des dépôts réels justifiant un `REPO-MAP.md` / `STATE.md`. Il **exclut** SANDBOX (hébergé) et
  Teams. C'est la lecture fidèle de « scope host + sujet » et l'alignement le plus proche du précédent
  des doctrines de sujet (SF-141), qui utilisent la même famille de prédicats.
- **Placement** : juste après `DESTINATION_ANNOUNCE_DOCTRINE` (thématiquement lié — la « carte » de faits
  durables), dans un bloc `if (workspace.isRunnerTarget())`, avant le bloc host-terminal-only.
- **Provider-First / Gateway-First** : consigne de conduite uniquement ; aucun moteur, aucun couplage
  Anthropic (préfixe destiné à l'`AiAgentProvider` abstrait).
