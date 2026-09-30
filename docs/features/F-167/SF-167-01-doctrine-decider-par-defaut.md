# Mini-spec — F-167 / SF-167-01 — Doctrine « décider par défaut et avancer »

## Identifiant

`F-167 / SF-167-01`

## Feature parente

`F-167` — Décider par défaut et avancer

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-167-01-doctrine-decider-par-defaut`

---

## Objectif

> En une phrase.

Injecter dans le prompt système du terminal une **doctrine stable** qui apprend à l'agent, sur un
choix à **faible enjeu / réversible**, à **choisir un défaut, l'annoncer et continuer** — et,
sur l'**irréversible / sensible**, à **ne jamais décider seul** mais à passer par une **question
structurée** (F-164 / `demander`).

---

## Comportement attendu

### Cas nominal

- **Choix réversible, faible enjeu** (nom de variable, emplacement d'un fichier de travail, ordre
  de deux étapes indépendantes, format d'une sortie) → l'agent **ne s'arrête pas** : il choisit un
  **défaut raisonnable**, l'**annonce** en une ligne (« je pars sur X — dis-moi si tu veux
  ajuster ») et **poursuit**.
- **Choix irréversible ou sensible** (ouvrir une MR/PR, apply/déploiement prod, suppression de
  données/fichiers, envoi externe e-mail/publication, dépense d'argent, opération destructive,
  changement de sécurité/permissions) → l'agent **ne décide pas seul** : il pose une **question
  structurée** via l'outil `demander` (F-164).
- **Ambiguïté / fort enjeu réversible** (réécriture large, choix d'archi engageant la suite) → la
  question reste préférable ; décider-par-défaut vise le **petit, local, sans regret**.

La doctrine est un **littéral stable** `DECIDE_BY_DEFAULT_DOCTRINE`, injecté dans
`AtelierChatService.buildSystemPrompt` de façon **inconditionnelle** (scope **universel**, sur les
deux cibles), **immédiatement après** `ASK_QUESTION_DOCTRINE` (F-164) — les deux moitiés du même
axe *demander ↔ ne pas demander* vivent côte à côte.

### Cas d'erreur / dégradés

| Situation | Comportement attendu |
|-----------|---------------------|
| Doute sur le caractère réversible d'un choix | Traiter comme **irréversible** → demander (la doctrine l'impose explicitement) |
| Personne au clavier (vague autonome) | Le comportement est inchangé côté code : la doctrine est du prompt-only ; l'escalade `demander` prend déjà l'option recommandée et la flague (F-164), aucune régression introduite ici |
| Préfixe système proche du plafond `SYSTEM_MAX_CHARS` | Littéral placé en tête du préfixe stable (avec les autres doctrines) → survit à la coupe ; couvert par le test d'overflow |

Aucun code HTTP : la subfeature ne touche ni endpoint, ni contrôleur, ni table.

---

## Critères d'acceptation

> Vérifiables, reviewés en PR.

- [ ] Le littéral `DECIDE_BY_DEFAULT_DOCTRINE` existe (`private static final String`) et est
  injecté dans `buildSystemPrompt`, **inconditionnellement**, juste après `ASK_QUESTION_DOCTRINE`.
- [ ] La doctrine est **présente sur une cible SANDBOX** (hébergé) — scope universel.
- [ ] La doctrine est **présente sur une cible RUNNER** (host / sujet) — scope universel.
- [ ] La doctrine porte le **cas nominal** : sur un choix faible enjeu / réversible, choisir un
  défaut + l'annoncer (« je pars sur ») + continuer.
- [ ] La doctrine porte le **garde-fou** : sur l'irréversible / sensible, décider-par-défaut est
  **INTERDIT** → question structurée (`demander`), avec **au moins un exemple** irréversible
  concret (ex. ouvrir une MR/PR, déploiement prod, suppression, envoi externe, dépense).
- [ ] La doctrine porte la règle **doute → traiter comme irréversible et demander**.
- [ ] La doctrine **référence `demander` (F-164)** pour l'escalade (ne la réimplémente pas).
- [ ] Le bloc de doctrine est **byte-stable** entre deux constructions du prompt (cache F-134).
- [ ] **Non-régression** : les doctrines voisines (`ASK_QUESTION_DOCTRINE`, annonce de
  destination, savoir durable) restent présentes et inchangées.
- [ ] **Aucun** impact sur l'isolation `user_id` (aucun accès données modifié).

---

## Périmètre

### Hors scope (explicite)

- Pas de **classifieur automatique** réversible/irréversible en code (le tri est un raisonnement
  du modèle, guidé par la doctrine).
- Pas de **nouvel outil** : l'escalade réutilise `demander` (F-164).
- Pas de table, endpoint, migration, ni changement d'UI.

---

## Préoccupation transversale — « prompt système partagé » ✅ COCHÉE

`buildSystemPrompt` construit la consigne système commune à tous les terminaux : toute injection y
est **transversale** (elle touche silencieusement toutes les cibles). Analyse d'impact obligatoire :

**Cibles d'injection impactées** (recensées) :

| Cible | Résolue par | Effet de SF-167-01 |
|-------|-------------|--------------------|
| SANDBOX (projet hébergé) | `buildSystemPrompt`, branche par défaut | Doctrine **ajoutée** (scope universel) |
| Terminal du poste (host) | `workspace.isHostTerminal()` | Doctrine **ajoutée** (universel) — coexiste avec `SUBJECT_ROUTING`/`SUBJECT_HANDOFF` host-only, inchangés |
| Terminal de sujet / projet (RUNNER) | `workspace.isRunnerTarget()` | Doctrine **ajoutée** (universel) — coexiste avec `DURABLE_KNOWLEDGE_*`, inchangés |
| Terminal Teams | même `buildSystemPrompt` | Doctrine **ajoutée** (universel) — cohérent : un choix réversible existe aussi là |
| Mode `ANSWER_PLAN` | `ANSWER_PLAN_DIRECTIVE` ajouté après | Aucune interaction : ordre des blocs inchangé |

**Nature du changement** : **strictement additif** — un `system.append(DECIDE_BY_DEFAULT_DOCTRINE)`
à un point fixe. Aucun bloc existant n'est retiré, réordonné ni modifié.

**Littéral stable** : `DECIDE_BY_DEFAULT_DOCTRINE` est une constante ; injecté inconditionnellement
au même point → le préfixe reste cacheable (F-134). Vérifié par un test byte-stable.

**Non-régression** : tests existants de `AtelierChatServiceSystemPromptTest` (présence des
doctrines voisines sur les deux cibles) inchangés + assertions ajoutées.

---

## Technique

### Fichiers impactés

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `backend/.../atelier/AtelierChatService.java` | Ajout d'un littéral + 1 `append` | Additif, point d'injection fixe (après `ASK_QUESTION_DOCTRINE`) |
| `backend/.../atelier/AtelierChatServiceSystemPromptTest.java` | Ajout de tests | Présence 2 cibles + garde-fou + byte-stable + non-régression |
| `docs/PRODUCT_SPEC.md` | F-167 ajoutée | Étape préalable (existence de la feature) |
| `docs/features/F-167/CADRAGE-F-167-...md` | Créé | Cadrage |

### Migration Liquibase

- [x] Non applicable.

### Composants Angular

- Aucun (doctrine prompt-only ; pas d'écran).

---

## Plan de test

### Tests unitaires (`AtelierChatServiceSystemPromptTest`)

- [ ] `theDecideByDefaultDoctrineIsPresentOnASandboxProject` — présence + marqueurs clés (amorce,
  « je pars sur », garde-fou irréversible, exemple MR/PR, doute → irréversible).
- [ ] `theDecideByDefaultDoctrineIsPresentOnARunnerProject` — présence sur cible RUNNER (universel)
  + non-régression rôle RUNNER.
- [ ] `theDecideByDefaultGuardrailForbidsDecidingAloneOnIrreversibleActions` — le garde-fou impose
  la question structurée (`demander`) sur un exemple irréversible ; décider-par-défaut INTERDIT.
- [ ] `theDecideByDefaultDoctrineIsByteStableBetweenTwoBuilds` — bloc identique à l'octet entre
  deux constructions (cache F-134), même patron que `theEnvironmentBlockIsByteStableBetweenTwoBuilds`.
- [ ] Non-régression : coexiste avec `ASK_QUESTION_DOCTRINE` et les doctrines voisines.

### Test d'overflow (`AtelierChatServicePromptOverflowTest`)

- [ ] La suite reste verte (le littéral vit en tête du préfixe stable, sous `SYSTEM_MAX_CHARS`).

### Isolation utilisateur

- [x] Non applicable — aucune requête ni accès données modifié. Le test l'atteste indirectement :
  `buildSystemPrompt` ne lit aucune donnée d'un autre `user_id`.

---

## Notes et décisions

- **Scope retenu : universel** (les deux cibles, inconditionnel), et non `isRunnerTarget()` comme
  F-166. Justification : F-167 est le complément de F-164 dont `ASK_QUESTION_DOCTRINE` est
  universelle ; l'outil `demander` est universel ; le choix à trancher est agnostique de la cible ;
  le garde-fou (MR/PR, prod, suppression, envoi, dépense) vaut partout. Détail dans le cadrage.
- **Réutilise F-164** : le texte de la doctrine renvoie à l'outil `demander` pour l'escalade — pas
  de réimplémentation (Provider-First / anti-duplication).
- **Gateway-First** : doctrine prompt-only, aucun moteur maison.
