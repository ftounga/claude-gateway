# Mini-spec — F-168 / SF-168-01 — Doctrine « vérifier avant de conclure »

## Identifiant

`F-168 / SF-168-01`

## Feature parente

`F-168` — Vérifier avant de conclure

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-168-01-doctrine-verifier-avant-conclure`

---

## Objectif

> En une phrase.

Injecter dans le prompt système du terminal une **doctrine stable** qui installe un **critère
d'arrêt fondé sur la preuve** : toute affirmation d'**état factuel** est **prouvée par une
vérification live** ou son **blocage précis est nommé**, l'agent **distingue mesuré vs supposé**,
**vérifie avant de déléguer**, **falsifie sa première hypothèse** et **étiquette « INCOMPLET »** une
investigation interrompue — sans pour autant transformer chaque tour en exploration exhaustive.

---

## Comportement attendu

### Cas nominal

- **État factuel à trancher** (une MR est-elle ouverte ? un fichier contient-il X ? une politique
  est-elle appliquée ?) → l'agent tranche par une **VÉRIFICATION LIVE** (lecture fichier, commande,
  API, state, logs, carte d'infra), **jamais** par une note (`STATE.md`), un doc ou une **déduction
  présentée comme un fait**.
- **Dans la réponse** → l'agent **distingue explicitement « mesuré » vs « supposé »**.
- **Avant de déléguer** (« à confirmer / non vérifié / je recommande de demander à X ») → il se
  demande d'abord « **puis-je répondre depuis le repo / cluster / state / logs / la carte ?** » et
  ne **délègue que si NON**, en **nommant le blocage précis** (droit refusé, auth humaine non
  scriptable, incident).
- **Avant de conclure** → il **falsifie sa 1ère hypothèse** par un contre-test sur un autre cas /
  une autre fenêtre.
- **Investigation interrompue / partielle** (notamment quand le tour touche le **plafond de
  consommation**) → il **ne la présente jamais comme finie** : il l'**étiquette « INCOMPLET —
  vérifications restantes : … »**.

La doctrine est un **littéral stable** `VERIFY_BEFORE_CONCLUDE_DOCTRINE`, injecté dans
`AtelierChatService.buildSystemPrompt` de façon **inconditionnelle** (scope **universel**, toutes
les cibles), **immédiatement après** `DECIDE_BY_DEFAULT_DOCTRINE` (F-167) — les trois moitiés du
même axe *demander (F-164) ↔ décider (F-167) ↔ prouver (F-168)* vivent côte à côte.

### Cas d'erreur / dégradés

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun moyen local de prouver l'état (droit refusé, auth non scriptable, incident) | La délégation devient **légitime** : l'agent **nomme le blocage précis** au lieu de supposer (report légitime, point 3) |
| Tour coupé au plafond de consommation en pleine investigation | L'agent a **déjà étiqueté « INCOMPLET — vérifications restantes »** (point 5) ; le message `SPEND_CAP_REPLY` **n'est pas modifié** (voir Notes) |
| Risque de sur-exploration (frugalité) | La doctrine **borne** l'exhaustivité aux affirmations d'état et à la porte avant délégation, **pas** à tout : garde-fou frugalité présent dans le texte |
| Préfixe système proche du plafond `SYSTEM_MAX_CHARS` | Littéral placé en tête du préfixe stable (avec les autres doctrines) → survit à la coupe ; couvert par le test d'overflow |

Aucun code HTTP : la subfeature ne touche ni endpoint, ni contrôleur, ni table.

---

## Critères d'acceptation

> Vérifiables, reviewés en PR.

- [ ] Le littéral `VERIFY_BEFORE_CONCLUDE_DOCTRINE` existe (`private static final String`) et est
  injecté dans `buildSystemPrompt`, **inconditionnellement**, juste après `DECIDE_BY_DEFAULT_DOCTRINE`.
- [ ] La doctrine est **présente sur une cible SANDBOX** (hébergé) — scope universel.
- [ ] La doctrine est **présente sur une cible RUNNER** (host / sujet) — scope universel.
- [ ] **Point 1** — vérification live d'un état factuel (lecture/commande/API/state/logs/carte),
  jamais une note (`STATE.md`) / doc / déduction présentée comme un fait.
- [ ] **Point 2** — distinguer explicitement **« mesuré » vs « supposé »** (obligatoire).
- [ ] **Point 3** — **porte avant délégation** : se demander « puis-je répondre depuis repo /
  cluster / state / logs / carte ? » ; ne déléguer qu'en **nommant le blocage précis**.
- [ ] **Point 4** — **falsifier** la 1ère hypothèse (contre-test) avant de conclure.
- [ ] **Point 5** — **marquage INCOMPLET** d'une investigation interrompue, en particulier au
  plafond de consommation.
- [ ] **Garde-fou frugalité** — l'exhaustivité porte sur les affirmations d'état + la porte avant
  délégation, **pas** sur une exploration systématique de tout.
- [ ] Le bloc de doctrine est **byte-stable** entre deux constructions du prompt (cache F-134).
- [ ] **Non-régression** : les doctrines voisines (`ASK_QUESTION_DOCTRINE`, `DECIDE_BY_DEFAULT_DOCTRINE`,
  annonce de destination, savoir durable) restent présentes et inchangées.
- [ ] **Aucun** impact sur l'isolation `user_id` (aucun accès données modifié).

---

## Périmètre

### Hors scope (explicite)

- Pas de **détection fragile de « type de tour »** ni de **compteur de vérifications** en code (le
  tri mesuré/supposé et la porte avant délégation sont un raisonnement du modèle, guidé par la
  doctrine).
- Pas de **lissage du message plafond** `SPEND_CAP_REPLY` (porté par la doctrine, point 5 — voir Notes).
- Pas d'**exploration systématique imposée** (garde-fou frugalité).
- Pas de **nouvel outil** : réutilise les outils de lecture / exécution existants.
- Pas de table, endpoint, migration, ni changement d'UI.

---

## Préoccupation transversale — « prompt système partagé » ✅ COCHÉE

`buildSystemPrompt` construit la consigne système commune à tous les terminaux : toute injection y
est **transversale** (elle touche silencieusement toutes les cibles). Analyse d'impact obligatoire :

**Cibles d'injection impactées** (recensées) :

| Cible | Résolue par | Effet de SF-168-01 |
|-------|-------------|--------------------|
| SANDBOX (projet hébergé) | `buildSystemPrompt`, branche par défaut | Doctrine **ajoutée** (scope universel) |
| Terminal du poste (host) | `workspace.isHostTerminal()` | Doctrine **ajoutée** (universel) — coexiste avec `SUBJECT_ROUTING`/`SUBJECT_HANDOFF` host-only, inchangés |
| Terminal de sujet / projet (RUNNER) | `workspace.isRunnerTarget()` | Doctrine **ajoutée** (universel) — coexiste avec `DURABLE_KNOWLEDGE_*`, inchangés |
| Terminal Teams | même `buildSystemPrompt` | Doctrine **ajoutée** (universel) — cohérent : une affirmation d'état existe aussi là |
| Mode `ANSWER_PLAN` | `ANSWER_PLAN_DIRECTIVE` ajouté après | Aucune interaction : ordre des blocs inchangé |

**Nature du changement** : **strictement additif** — un `system.append(VERIFY_BEFORE_CONCLUDE_DOCTRINE)`
à un point fixe (après `DECIDE_BY_DEFAULT_DOCTRINE`). Aucun bloc existant n'est retiré, réordonné ni
modifié.

**Littéral stable** : `VERIFY_BEFORE_CONCLUDE_DOCTRINE` est une constante ; injecté inconditionnellement
au même point → le préfixe reste cacheable (F-134). Vérifié par un test byte-stable.

**Non-régression** : tests existants de `AtelierChatServiceSystemPromptTest` (présence des
doctrines voisines sur les deux cibles) inchangés + assertions ajoutées.

---

## Technique

### Fichiers impactés

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `backend/.../atelier/AtelierChatService.java` | Ajout d'un littéral + 1 `append` | Additif, point d'injection fixe (après `DECIDE_BY_DEFAULT_DOCTRINE`) |
| `backend/.../atelier/AtelierChatServiceSystemPromptTest.java` | Ajout de tests | Présence 2 cibles + 5 points + garde-fou frugalité + byte-stable + non-régression |
| `docs/PRODUCT_SPEC.md` | F-168 ajoutée | Étape préalable (existence de la feature) |
| `docs/features/F-168/CADRAGE-F-168-...md` | Créé | Cadrage |

### Migration Liquibase

- [x] Non applicable.

### Composants Angular

- Aucun (doctrine prompt-only ; pas d'écran).

---

## Plan de test

### Tests unitaires (`AtelierChatServiceSystemPromptTest`)

- [ ] `theVerifyBeforeConcludeDoctrineIsPresentOnASandboxProject` — présence + marqueurs des 5 points
  (vérification live, mesuré/supposé, porte avant délégation, falsifier, INCOMPLET) + garde-fou frugalité.
- [ ] `theVerifyBeforeConcludeDoctrineIsPresentOnARunnerProject` — présence sur cible RUNNER
  (universel) + non-régression rôle RUNNER.
- [ ] `theVerifyBeforeConcludeGuardrailKeepsFrugality` — l'exhaustivité porte sur les affirmations
  d'état + la porte avant délégation, **pas** sur l'exploration systématique de tout.
- [ ] `theVerifyBeforeConcludeDoctrineIsByteStableBetweenTwoBuilds` — bloc identique à l'octet entre
  deux constructions (cache F-134), même patron que `theDecideByDefaultDoctrineIsByteStableBetweenTwoBuilds`.
- [ ] Non-régression : coexiste avec `ASK_QUESTION_DOCTRINE` et `DECIDE_BY_DEFAULT_DOCTRINE`.

### Test d'overflow (`AtelierChatServicePromptOverflowTest`)

- [ ] La suite reste verte (le littéral vit en tête du préfixe stable, sous `SYSTEM_MAX_CHARS`).

### Isolation utilisateur

- [x] Non applicable — aucune requête ni accès données modifié. Le test l'atteste indirectement :
  `buildSystemPrompt` ne lit aucune donnée d'un autre `user_id`.

---

## Notes et décisions

- **Scope retenu : universel** (toutes les cibles, inconditionnel), et non `isRunnerTarget()` comme
  F-166. Justification (prédicat de scope) : la doctrine **régit toute affirmation d'état factuel**,
  pas une cible précise — un « MR fermée », un « plus appliqué depuis… », un « le fichier contient
  X » s'affirment depuis n'importe quelle cible ; la **porte avant délégation** vaut partout ; l'axe
  *demander (F-164) ↔ décider (F-167) ↔ prouver (F-168)* est universel de bout en bout. Détail dans
  le cadrage.
- **Message plafond `SPEND_CAP_REPLY` — NON touché (décision par défaut, flaguée)** : il est émis
  pour **TOUS** les tours coupés au plafond, pas seulement les tours d'investigation. Le lisser pour
  cadrer l'INCOMPLET exigerait soit de **détecter « tour d'investigation »** (fragile, explicitement
  déconseillé), soit d'**imposer un cadre d'investigation à tout tour coupé** (bruit, faux hors
  investigation). L'**INCOMPLET est porté par la doctrine (point 5)** : l'agent s'auto-étiquette
  avant que le plafond ne tombe. Choix propre, additif, byte-stable.
- **Réutilise les outils existants** : la doctrine renvoie aux lectures / commandes / appels d'API
  déjà outillés — pas de réimplémentation (Provider-First / anti-duplication).
- **Gateway-First** : doctrine prompt-only, aucun moteur maison.
