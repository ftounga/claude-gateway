# Mini-spec — F-166 / SF-166-02 — Déclencheur léger après N lectures sans artefact

## Identifiant

`F-166 / SF-166-02`

## Feature parente

`F-166` — Savoir durable (REPO-MAP.md / STATE.md entretenus)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-166-02-declencheur-carte`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Ajouter au prompt système du terminal (host + sujet) un **déclencheur léger, prompt-first** qui apprend
à l'agent à **s'auto-surveiller** : après avoir ouvert plusieurs fichiers d'un même dépôt / sujet pour
se ré-orienter **sans qu'un `REPO-MAP.md` / `STATE.md` existe**, il **propose d'en créer un avant de
continuer** — sans jamais bloquer ni remplacer la lecture réelle d'un fichier.

---

## Comportement attendu

### Cas nominal

1. Un tour s'ouvre sur un workspace du **poste** (`isRunnerTarget()` : terminal du poste **ou** terminal
   de sujet). `buildSystemPrompt` assemble le préfixe.
2. Le nouveau littéral `DURABLE_KNOWLEDGE_TRIGGER_DOCTRINE` est **ajouté** au préfixe, **immédiatement
   après** `DURABLE_KNOWLEDGE_DOCTRINE` (SF-166-01), dans le **même bloc** `if (workspace.isRunnerTarget())`.
3. La doctrine énonce le **critère de déclenchement auto-observable** : « quand tu constates que tu as
   déjà **ouvert plusieurs fichiers du même dépôt / sujet** pour te ré-orienter (dans ce tour ou d'un
   tour à l'autre) et qu'**aucun** `REPO-MAP.md` / `STATE.md` n'est présent, **ce re-scan EST le
   signal** : marque une pause et **propose** d'en créer un (bornée : `git ls-files` + points d'entrée)
   **avant de continuer à re-explorer**. »
4. **Anti-spam** : la proposition est faite **au plus une fois** dans le fil ; un « non » suffit et n'est
   pas re-demandé ; le déclencheur ne remplace **jamais** la lecture du fichier dont l'agent a besoin
   maintenant (on lit d'abord, on propose ensuite / en parallèle, on ne bloque pas).
5. Le reste du préfixe (rôle, environnement, doctrines existantes dont `DURABLE_KNOWLEDGE_DOCTRINE`,
   CLAUDE.md, état du sujet, skills, outils) est **inchangé à l'octet près** hormis l'ajout du bloc →
   cache F-134 préservé.

### Cas d'erreur

> Cette subfeature n'ajoute ni endpoint ni I/O : les « cas d'erreur » sont des cas de conduite couverts
> par le littéral, plus la robustesse de scope. **Aucun compteur en code** n'est introduit (voir §Notes).

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Un artefact (`REPO-MAP.md` / `STATE.md`) est **déjà présent** | Aucune suggestion : la doctrine SF-166-01 (« lire l'artefact d'abord ») reprend ; le déclencheur ne s'arme pas | N/A |
| L'agent n'a lu **qu'un ou deux fichiers** (pas de re-scan) | Aucune suggestion : le seuil « plusieurs fichiers » n'est pas atteint ; pas de spam sur une exploration légère | N/A |
| Workspace **hors scope** (SANDBOX hébergé, terminal Teams) | Le déclencheur est **absent** du préfixe (préfixe plus court, cache préservé) | N/A |
| L'utilisateur a **déjà refusé** dans le fil | Ne pas re-proposer ; la lecture réelle continue normalement | N/A |
| L'agent a **besoin d'un fichier précis maintenant** | Il l'**ouvre d'abord** ; la suggestion ne bloque ni ne remplace jamais cette lecture | N/A |

---

## Critères d'acceptation

> Chaque critère est vérifiable en test unitaire sur `buildSystemPrompt`.

- [ ] Le déclencheur est **présent** dans le préfixe d'un **terminal de poste** (`isHostTerminal()`).
- [ ] Le déclencheur est **présent** dans le préfixe d'un **terminal de sujet / projet RUNNER**
      (`isRunnerTarget()` sans `isHostTerminal()`).
- [ ] Le déclencheur est **absent** du préfixe d'un **projet hébergé SANDBOX** (hors scope).
- [ ] Le littéral énonce sans ambiguïté : le **signal** (plusieurs fichiers du même dépôt ouverts pour
      se ré-orienter, **sans** `REPO-MAP.md` / `STATE.md`) ; **propose avant de continuer à re-explorer** ;
      **une seule fois** (anti-spam) ; **ne bloque ni ne remplace jamais** la lecture réelle d'un fichier.
- [ ] **Non-régression / additivité** : la doctrine SF-166-01 (`DURABLE_KNOWLEDGE_DOCTRINE`,
      « Entretiens un savoir durable du dépôt ») reste **présente et inchangée** à côté de l'ajout ; les
      doctrines universelles voisines (F-125, SF-141-01) restent présentes.
- [ ] **Non-régression cache / stabilité** : le bloc du déclencheur est **byte-stable entre deux
      constructions identiques**.
- [ ] **Isolation inchangée** : aucun accès aux données, aucun filtre `user_id` touché (l'injection ne
      lit ni n'écrit de donnée applicative).

---

## Périmètre

### Hors scope (explicite)

- Tout **compteur / machine à états persistante** (table, colonne, index) : **rien de persistant**.
  L'auto-surveillance vit dans le prompt (le modèle observe son propre historique d'appels d'outils du
  tour) — voir §Notes pour la justification « minimum ».
- Tout **nouvel outil**, endpoint, table ou migration.
- Toute **écriture automatique** d'un artefact sans proposition (l'utilisateur décide).
- Tout **index persistant en base / embeddings dédiés** (sémantique = F-162 recall).
- Workspaces **SANDBOX** (hébergé) et terminaux **Teams**.
- Rendu / composant **frontend** (cette subfeature est prompt-only, backend).

---

## Valeurs initiales

Sans objet — aucune entité créée, aucun état de ressource modifié.

---

## Contraintes de validation

Sans objet — aucun champ utilisateur, aucune entrée validée. Le seul « champ » est un **littéral
constant** compilé (`private static final String DURABLE_KNOWLEDGE_TRIGGER_DOCTRINE`), stable par
construction.

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
| `backend/src/main/java/fr/claudegateway/atelier/AtelierChatService.java` | Ajout du littéral `DURABLE_KNOWLEDGE_TRIGGER_DOCTRINE` + son injection scopée dans `buildSystemPrompt` (dans le bloc `isRunnerTarget()` existant, juste après `DURABLE_KNOWLEDGE_DOCTRINE`) |
| `backend/src/test/java/fr/claudegateway/atelier/AtelierChatServiceSystemPromptTest.java` | Tests : présence (host + sujet), absence (SANDBOX), contenu, non-régression SF-166-01, stabilité byte |

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
| `DESTINATION_ANNOUNCE_DOCTRINE` | universelle | **Inchangée** |
| `DURABLE_KNOWLEDGE_DOCTRINE` (SF-166-01) | host + sujet (`isRunnerTarget()`) | **Inchangée** (l'ajout se place juste après) |
| **`DURABLE_KNOWLEDGE_TRIGGER_DOCTRINE`** (NOUVEAU) | **host + sujet** (`isRunnerTarget()`) | **AJOUT additif** |
| `SUBJECT_ROUTING_DOCTRINE` / `SUBJECT_HANDOFF_DOCTRINE` | host-terminal-only | **Inchangées** |
| `ANSWER_PLAN_DIRECTIVE` (mode) | conditionnel mode | **Inchangée** |
| `SECTION_TOOLS` + `toolChoiceSection` | universelle | **Inchangée** |
| Notices de volets (Teams / Radar / pages / présentations / images…) | conditionnelles | **Inchangées** |
| Préambule + CLAUDE.md projeté, règles de gouvernance | conditionnels | **Inchangés** |
| État courant du sujet (`SUBJECT_STATE_*`, F-148) | sujet | **Inchangé** |
| Catalogue de skills, arbre | universel | **Inchangé** |

**Confirmation :**

- L'ajout est **strictement additif** : aucune cible existante n'est retirée, réordonnée ni modifiée.
  `DURABLE_KNOWLEDGE_DOCTRINE` (SF-166-01) reste **intacte à l'octet près**, le nouveau bloc vient
  **après** elle.
- Le nouveau bloc est un **littéral stable** (`private static final String`) — point d'insertion et
  contenu constants → **cache de prompt F-134 préservé** (aucune volatilité de préfixe introduite).
- Le scope `isRunnerTarget()` (host + sujet) est **identique** à celui de SF-166-01 ; hors scope
  (SANDBOX, Teams), le préfixe reste **plus court** (cache préservé).

---

## Plan de test

### Tests unitaires (`AtelierChatServiceSystemPromptTest`, `buildSystemPrompt` via `systemPrompt()`)

- [ ] `theDurableKnowledgeTriggerIsPresentOnTheHostTerminal` — présent sur terminal du poste, + son
      contenu clé (le signal « plusieurs fichiers sans REPO-MAP/STATE », proposer avant de continuer,
      une seule fois, ne bloque/remplace jamais la lecture réelle).
- [ ] `theDurableKnowledgeTriggerIsPresentOnARunnerProject` — présent sur terminal de sujet / projet
      RUNNER ; + non-régression : `DURABLE_KNOWLEDGE_DOCTRINE` (SF-166-01) toujours présente.
- [ ] `theDurableKnowledgeTriggerIsAbsentOnASandboxProject` — absent sur projet hébergé SANDBOX ; les
      doctrines universelles restent (seul le bloc host+sujet manque).
- [ ] `theDurableKnowledgeTriggerIsByteStableBetweenTwoBuilds` — le bloc du déclencheur est identique à
      l'octet entre deux constructions (cache F-134).

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

- **SF-166-01** (livrée, PR #1025) : `DURABLE_KNOWLEDGE_DOCTRINE` est déjà sur `main` ; SF-166-02 s'y
  adosse et clôt la feature F-166.

### Questions ouvertes impactées

- Aucune. (Le sémantique persistant reste explicitement à F-162 ; pas d'OQ rouverte.)

---

## Notes et décisions

- **DÉCISION DE CONCEPTION (par défaut, flaguée) — doctrine SEULE, AUCUN compteur en code.** Le cadrage
  (§6) décrivait SF-166-02 comme « un signal conversationnel qui, après N re-scans du même repo sans
  artefact, rappelle la proposition ». Deux implémentations étaient possibles : (a) un **compteur au
  niveau du tour** dans la boucle qui injecterait un nudge après N lectures, ou (b) une **doctrine
  prompt-first** qui rend le critère **auto-observable** par le modèle. Nous retenons **(b)**, pour ces
  raisons :
  1. **Minimum réel.** Le modèle dispose déjà de son propre **historique d'appels d'outils** dans la
     fenêtre de messages du tour ; il peut donc constater lui-même « j'ai ouvert plusieurs fichiers de
     ce dépôt et il n'y a pas de REPO-MAP/STATE » **sans aucun compteur en code**. Un compteur devrait
     classer quels appels sont des « lectures de fichiers d'un dépôt », suivre les fichiers distincts,
     et **détecter la présence de l'artefact** — exactement la « grosse machine à états » que la
     consigne PO demande d'éviter, et incohérent avec « le tour vit dans le flux » + Gateway-First.
  2. **Règle absolue PO (justesse avant coût, additivité).** Injecter un nudge dynamique **casserait la
     stabilité du préfixe** (cache F-134) et risquerait d'**interrompre / remplacer** une lecture réelle.
     Un **littéral stable** préserve le cache et garantit que la suggestion **ne bloque ni ne remplace
     jamais** la lecture nécessaire — le déclencheur ne fait que **rappeler** de proposer.
  3. **Cohérence de patron.** Toutes les doctrines voisines (SF-141, F-164, SF-166-01) sont des
     littéraux stables scopés ; SF-166-02 suit le même patron, ce qui minimise la surface de risque.
- **Placement** : juste **après** `DURABLE_KNOWLEDGE_DOCTRINE`, dans le **même** bloc
  `if (workspace.isRunnerTarget())`. Thématiquement lié (c'est le déclencheur concret de la même
  doctrine) et scope identique.
- **Distinction avec SF-166-01** : SF-166-01 pose *quoi* faire (lire d'abord, proposer aux moments clés,
  bornée, pointeur). SF-166-02 rend *quand* proposer **concret et auto-observable** : le re-scan répété
  sans artefact **est** le signal — c'est le « déclencheur léger » qui attrape le cas gitlab-tfstate
  (même dépôt redécouvert tour après tour).
- **Provider-First / Gateway-First** : consigne de conduite uniquement ; aucun moteur, aucun couplage
  Anthropic (préfixe destiné à l'`AiAgentProvider` abstrait).
