# Mini-spec — [F-171 / SF-171-01] Déplacer STATE/PLAN + arborescence du système vers le message

## Identifiant

`F-171 / SF-171-01`

## Feature parente

`F-171` — Préfixe stable (effondrer le cache_write)

## Statut

`in-progress`

## Date de création

2026-10-01

## Branche Git

`feat/SF-171-01-prefixe-stable-systeme`

---

## Objectif

> En une phrase : sortir le contenu **volatil par tour** (`STATE.md` + `PLAN-ACTION.md` + arborescence/catalogue de skills) du **bloc système** de l'Atelier et l'injecter **dans le message du tour**, à l'octet près, pour que le préfixe système redevienne byte-stable et que l'historique repasse en cache-READ.

---

## Comportement attendu

### Cas nominal

1. À chaque tour (`runLoop`), la construction du prompt lit (comme avant) `CLAUDE.md`, les règles de
   gouvernance, `STATE.md`/`PLAN-ACTION.md` du sujet, l'arborescence et le catalogue de skills.
2. **`CLAUDE.md`, les règles de gouvernance, les doctrines, le bloc environnement, le sommaire de
   carte (`hostOutline`)** restent dans le **bloc système** (inchangés, byte-identiques à avant).
3. **`STATE.md` + `PLAN-ACTION.md`** (bloc « État courant du sujet ») et le **catalogue de skills**
   (dérivé de l'arborescence) sont désormais **préfixés à la `consigne`** (le message du tour),
   exactement comme les faits datés (`factsFor`), la conclusion rappelée (`recall`) et le plan
   reporté (`carriedPlanNote`) — donc **sous le dernier breakpoint** de cache.
4. Le **contenu** déplacé est **byte-identique** à ce qui était injecté dans le système : mêmes
   en-têtes (`--- État courant du sujet (STATE.md / PLAN-ACTION.md) ---`, `### STATE.md`,
   `--- Skills du projet … ---`, `Ouvre un skill avec read_file …`), mêmes bornes (6000 c./fichier,
   `… (état tronqué)`), même limite de skills annoncés (`MAX_SKILLS_ANNOUNCED`).
5. Le **bloc système** est **byte-stable** entre deux tours même si STATE/PLAN/arborescence ont
   changé entre les deux — c'est la condition du gain de cache (F-134).
6. Le **comptage d'amorçage** (`recordBootstrap`, cible RUNNER, SF-38-08, aggrégé en UNE ligne)
   continue de compter **l'ensemble** des lectures (CLAUDE.md + STATE + PLAN + listage + skills) :
   le total de lectures par tour est **inchangé** (les fichiers sont toujours lus une fois chacun).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| STATE.md / PLAN-ACTION.md absents ou vides | Simplement omis (repli passant), aucun bloc ajouté — comme avant |
| Au terminal du poste (pas de sujet courant) | Aucun état de sujet injecté — comme avant |
| Arborescence vide / skill illisible | Catalogue omis ou skill ignoré, jamais bloquant — comme avant |
| Contenu STATE/PLAN trop long | Tronqué à 6000 c. + mention `… (état tronqué)` — comme avant |

---

## Critères d'acceptation

- [ ] Le bloc système produit par `buildSystemPrompt` **ne contient plus** l'en-tête « État courant du sujet » ni le bloc STATE/PLAN.
- [ ] Le bloc système produit par `buildSystemPrompt` **ne contient plus** le catalogue « Skills du projet ».
- [ ] Le bloc système est **byte-identique entre deux tours** alors que STATE/PLAN ont changé entre les deux (test qui prouve le gain).
- [ ] Le **contenu STATE/PLAN** (en-tête + `### STATE.md` + corps + `### PLAN-ACTION.md` + corps) apparaît dans le **message du tour** envoyé au fournisseur, **à l'octet près**.
- [ ] Le **catalogue de skills** apparaît dans le **message du tour**, à l'octet près (chemin + description, jamais le corps du skill).
- [ ] Les bornes (6000 c./fichier + `… (état tronqué)`) et la limite `MAX_SKILLS_ANNOUNCED` sont préservées, appliquées au contenu déplacé.
- [ ] Les lectures STATE/PLAN/skills restent **scoppées (user_id, workspace_id)** du tour (isolation inchangée).
- [ ] `recordBootstrap` (RUNNER) compte toujours **l'ensemble** des lectures d'amorçage en une ligne (total inchangé : CLAUDE.md + STATE + PLAN + listage + skills).
- [ ] Les doctrines, l'environnement, `CLAUDE.md`, les règles de gouvernance et le sommaire de carte restent **inchangés** dans le système.

---

## Périmètre

### Hors scope (explicite)

- Le **sommaire de carte** (`hostOutline`) : **non touché** (déjà stable, ce n'est pas le driver).
- Les **faits** (`factsFor`) : déjà dans le message, non touchés.
- Fix C (TTL relais passerelle) : c'est **SF-171-02**.
- Aucun filtrage/priorisation de l'arborescence (« carte par pertinence » écartée).
- Aucune nouvelle table / endpoint / migration / composant Angular.

---

## Contraintes de validation

| Élément | Règle préservée |
|---------|-----------------|
| Borne STATE/PLAN | 6000 caractères/fichier (`SUBJECT_STATE_MAX_CHARS`), suffixe `\n… (état tronqué)\n` |
| Skills annoncés | `MAX_SKILLS_ANNOUNCED` (15), corps jamais déversé |
| En-têtes | `SUBJECT_STATE_HEADER`, `--- Skills du projet (lis le fichier …) ---`, pied `Ouvre un skill …` — inchangés |
| Isolation | lectures via `promptFile` / `safeTree` / `promptSource`, scoppées (user_id, workspace_id) |

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. Chemin interne : `AtelierChatService.runLoop` → construction du prompt.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

Aucun (backend seul ; le contenu reste envoyé au modèle, l'écran est inchangé — le message
**persisté** reste la parole de l'utilisateur, comme pour `factsFor`/`recall`).

### Composants backend impactés (préoccupation transversale « assemblage du prompt + cache »)

- `AtelierChatService.buildSystemPrompt` / nouvelle structure `buildPrompt` (renvoie système + contexte volatil).
- `AtelierChatService.runLoop` (préfixe le contexte volatil à la `consigne`, même patron que `factsFor`/`recall`/`carriedPlanNote`).
- `AnthropicAgentProvider` : **non modifié** (le breakpoint dernier message porte déjà le contenu déplacé).
- Tests impactés : `AtelierChatServiceSubjectStateTest`, `AtelierChatServiceSystemPromptTest` (test skills), `AtelierChatServiceRunnerGuardTest` (recordBootstrap==6 doit rester vert), `AtelierChatServicePromptOverflowTest` (non impacté, tree vide).

---

## Plan de test

### Tests unitaires

- [ ] `buildSystemPrompt` : STATE/PLAN **absents** du bloc système (adapter `AtelierChatServiceSubjectStateTest`).
- [ ] `buildSystemPrompt` : catalogue de skills **absent** du bloc système (adapter `AtelierChatServiceSystemPromptTest.skillIsAnnounced…`).
- [ ] **Byte-stabilité du système** : deux tours avec STATE/PLAN **différents** entre les deux → `system(tour1) == system(tour2)` (LE test du gain).
- [ ] STATE/PLAN présents dans le **message** du tour (via `messageSnapshots` / `lastRequest.messages()`), à l'octet près (en-tête + contenu).
- [ ] Catalogue de skills présent dans le **message**, corps du skill jamais présent.
- [ ] Repli : STATE/PLAN absents → ni système ni message ne portent le bloc.
- [ ] Terminal du poste : aucun état de sujet (ni système, ni message).
- [ ] Troncature : état > 6000 c. tronqué + mention, dans le message.

### Tests d'intégration

- [ ] `recordBootstrap` reste à **6 lectures** en une ligne (`AtelierChatServiceRunnerGuardTest.bootstrapReadsAreAggregatedIntoASingleAuditLine`).

### Isolation workspace

- [ ] Applicable — lectures STATE/PLAN/skills toujours via helpers scoppés (user_id, workspace_id) ; test existant `stateReadsAreScopedToTheTurn` adapté (toujours vert).

---

## Dépendances

### Subfeatures bloquantes

Aucune.

### Questions ouvertes impactées

Aucune (pas de sujet `OPEN_QUESTIONS.md` touché).

---

## Notes et décisions

- **Décision par défaut (flaguée)** : `recordBootstrap` est conservé **en une seule ligne** comptant
  **tout** l'amorçage (total de lectures inchangé). Pour cela, la construction du prompt reste
  **unifiée** (une méthode `buildPrompt` qui lit tout et renvoie `{système, contexte volatil}`),
  plutôt que deux lectures séparées qui auraient dédoublé ou faussé la ligne d'audit D11.
- **Effet de bord positif (flagué)** : le contenu STATE/PLAN/skills n'est plus soumis à la coupe
  `SYSTEM_MAX_CHARS` (40 000) du bloc système (il vit désormais dans le message, déjà borné par
  fichier). Dans le cas nominal (système < 40 k) le contenu vu par le modèle est **identique** ; en
  cas de système volumineux, le contenu déplacé n'est **plus** tronqué par cette coupe — donc **zéro
  perte de contexte** (conforme « déplacer ≠ retirer », strictement ≥ l'ancien contenu).
- **Position dans le message** : le contexte volatil est préfixé **en tête** de la `consigne` (après
  les autres préfixes), ce qui le place immédiatement après l'historique rejoué — soit la même
  position relative qu'avant dans le flux de tokens (il était en fin de système, juste avant le
  message), mais désormais **sous le dernier breakpoint**.
