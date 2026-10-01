# Mini-spec — [F-166 / SF-166-03] Brancher le savoir durable sur la gouvernance

---

## Identifiant

`F-166 / SF-166-03`

## Feature parente

`F-166` — Savoir durable (réouverture : F-166 Terminée → En cours → Terminée)

## Statut

`in-progress`

## Date de création

2026-10-01

## Branche Git

`feat/SF-166-03-brancher-savoir-durable-sur-gouvernance`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Aligner la doctrine « savoir durable » sur le **circuit de gouvernance réel** du poste — enrichir
`regles.md` du paquet `savoir-durable` du volet « **lire la carte d'abord pour s'orienter** » et
réduire la doctrine codée en dur dans `AtelierChatService` à un **socle universel court** qui abandonne
le `REPO-MAP.md` inventé par F-166 au profit des noms existants (`PLAN-ACTION.md` / `STATE.md` / carte
racine).

---

## Contexte & problème corrigé

F-166 (SF-166-01/02) a codé en dur **deux** doctrines (`DURABLE_KNOWLEDGE_DOCTRINE` et
`DURABLE_KNOWLEDGE_TRIGGER_DOCTRINE`) qui inventent un fichier **`REPO-MAP.md`** — un nom **hors du
circuit de gouvernance** : ni lu par le moteur de carte (`HostMapKnowledgeProvider`), ni vérifié par
les contrôles, ni versionné. Résultat : doublon et vocabulaire divergent avec le paquet
`savoir-durable` (qui, lui, utilise `PLAN-ACTION.md`, la carte racine et `STATE.md`).

Par ailleurs, le paquet `savoir-durable` (`regles.md`) porte déjà le volet **promotion** (« le travail
est jetable, le savoir est durable… ranger le durable dans la carte ») mais **pas** le volet symétrique
**lecture d'abord** (« lire la carte pour s'orienter au lieu de re-scanner »).

---

## Comportement attendu

### Cas nominal

**Volet A — `regles.md` (postes gouvernés, paquet activé) :** ajout d'un volet « s'orienter : lire la
carte avant de re-scanner ». Quand l'agent attaque un dépôt / reprend un sujet, il **lit d'abord** la
carte du poste, le `PLAN-ACTION.md` du projet et son `STATE.md` pour s'orienter plutôt que de re-scanner
l'arborescence et tout re-dériver. La carte est un **pointeur à revérifier** (jamais un substitut à la
lecture du fichier réel quand la précision compte). Si la carte manque, construction **bornée**
(`git ls-files` + points d'entrée, pas un audit complet). Vocabulaire aligné sur les fichiers
**existants** — aucun `REPO-MAP.md`. Au prochain démarrage, `GovernancePackageSeeder` incrémente
automatiquement la version du paquet (hors scope de cette SF).

**Volet B — `AtelierChatService` (TOUS les postes, même sans paquet activé) :** le prompt système
injecte, sous `isRunnerTarget()` (terminal du poste + terminal de sujet), **un seul** littéral court
`DURABLE_KNOWLEDGE_DOCTRINE` — le **socle universel plancher** : « s'il existe une carte /
`PLAN-ACTION.md` / `STATE.md`, lis-la d'abord pour t'orienter ; ne re-scanne pas tout si un résumé
existe ; la carte est un pointeur à revérifier, jamais un substitut à la lecture réelle ». L'essence du
déclencheur SF-166-02 (le re-scan répété EST le signal → proposer une carte bornée) est **fondue en une
phrase** dans ce socle. `DURABLE_KNOWLEDGE_TRIGGER_DOCTRINE` est **supprimé**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Projet hébergé (SANDBOX, hors poste) | `isRunnerTarget()` faux → socle **absent**, préfixe plus court (cache F-134 préservé) |
| Terminal Teams | hors scope `isRunnerTarget()` → socle absent |
| Paquet `savoir-durable` non activé sur le poste | `regles.md` non injecté dans la consigne, mais **le socle universel reste** (plancher pour tous) ; léger recouvrement acceptable sur un poste gouverné |
| `PLAN-ACTION.md` / `STATE.md` / carte absents | la doctrine n'exige rien ; propose (borné) sans bloquer la lecture réelle |

---

## Critères d'acceptation

- [ ] `regles.md` du paquet `savoir-durable` porte un volet « lire la carte d'abord » utilisant **uniquement** les noms existants (`PLAN-ACTION.md`, `STATE.md`, carte racine), **aucun** `REPO-MAP.md`
- [ ] `regles.md` conserve intégralement son contenu antérieur (promotion, livrables, invariants de clonage, second regard) — ajout **strictement additif**
- [ ] `AtelierChatService` n'expose plus qu'**un** littéral `DURABLE_KNOWLEDGE_DOCTRINE`, court, injecté sous `isRunnerTarget()`
- [ ] `DURABLE_KNOWLEDGE_TRIGGER_DOCTRINE` est supprimé ; son essence (re-scan = signal → proposer) figure en une phrase du socle
- [ ] La chaîne `REPO-MAP` n'apparaît **plus nulle part** dans le code backend (`src/main`)
- [ ] Le socle cite `PLAN-ACTION.md`, `STATE.md` et la carte ; dit « lis-la d'abord », « pointeur à revérifier », « jamais un substitut à la lecture réelle »
- [ ] L'injection reste sous `isRunnerTarget()` (host + sujet), additive, littéral stable (byte-stable entre deux builds — cache F-134)
- [ ] Aucune autre doctrine n'est retirée ni réordonnée
- [ ] Build backend vert ; `AtelierChatServiceSystemPromptTest`, `AtelierChatServicePromptOverflowTest` et les tests `governance/*` verts

---

## Périmètre

### Hors scope (explicite)

- Aucune modification du **moteur de carte** (`HostMapKnowledgeProvider`) ni de la politique **opt-in** du paquet
- Aucune modification du **versioning** du paquet (`GovernancePackageSeeder` incrémente seul)
- Aucune table / endpoint / migration / frontend / nouvel outil
- Pas de conditionnement de l'injection du socle à l'activation du paquet (recouvrement léger accepté)

---

## Préoccupation transversale — « prompt système partagé » (COCHÉE)

Modification d'un littéral injecté dans `AtelierChatService.buildSystemPrompt` → impact potentiel sur
toutes les cibles d'injection. **Analyse d'impact (composants impactés) :**

| Cible d'injection | Avant | Après |
|---|---|---|
| Terminal du poste (`isHostTerminal()` ⊂ `isRunnerTarget()`) | `DURABLE_KNOWLEDGE_DOCTRINE` + `DURABLE_KNOWLEDGE_TRIGGER_DOCTRINE` | **socle unique** `DURABLE_KNOWLEDGE_DOCTRINE` (court) |
| Terminal de sujet RUNNER (`isRunnerTarget()`) | idem | **socle unique** |
| Projet SANDBOX (hébergé) | absent | absent (inchangé) |
| Terminal Teams | absent | absent (inchangé) |
| Mode `ANSWER_PLAN` | inchangé | inchangé |

- **Littéral stable** : constant, injecté à point fixe → byte-stable (test dédié).
- **Ajout/retrait maîtrisé** : un littéral retiré (`TRIGGER`), un littéral raccourci ; **aucune autre
  doctrine touchée ni réordonnée** (ordre : `DESTINATION_ANNOUNCE_DOCTRINE` → socle → aiguillage host).
- **Cache F-134** : le préfixe reste stable à l'octet ; la modification est un one-shot assumé (le
  préfixe change une fois à la livraison, comme toute évolution de doctrine).

---

## Plan de test

### Tests unitaires (`AtelierChatServiceSystemPromptTest`)

- [ ] `buildSystemPrompt` sur terminal du poste : socle présent (amorce + `PLAN-ACTION.md` + `STATE.md` + « lis-la d'abord » + pointeur à revérifier + substitut à la lecture réelle + essence re-scan)
- [ ] `buildSystemPrompt` sur terminal de sujet RUNNER : socle présent
- [ ] Projet SANDBOX : socle absent, doctrines universelles présentes (non-régression)
- [ ] **`REPO-MAP` absent du prompt** sur toutes les cibles
- [ ] Byte-stable entre deux builds (cache F-134)
- [ ] Adapter les tests SF-166-01/02 (marqueur, assertions) ; retirer les tests du `TRIGGER` supprimé (essence couverte par le socle) — documenté, non supprimé « à la légère »

### Tests d'intégration / ressource

- [ ] `GovernancePackageSeederTest` reste vert (ajout additif à `regles.md` ne casse pas les invariants de clonage ni la nuance de destination)
- [ ] Pas de nouveau test sur `regles.md` (ressource) — le test existant `shippedRulesCarryTheDestinationNuance` suffit et reste vert

### Isolation workspace

- [ ] Non applicable — raison : consigne de conduite dans le prompt système, aucun accès données, isolation `user_id` inchangée.

---

## Dépendances

### Subfeatures bloquantes

- `SF-166-01`, `SF-166-02` — statut : done (cette SF les refond)

### Questions ouvertes impactées

- [ ] Aucune (`docs/OPEN_QUESTIONS.md` non impacté)

---

## Notes et décisions

- **Décision** : `DURABLE_KNOWLEDGE_TRIGGER_DOCTRINE` **supprimé** (plutôt que conservé) et son essence
  fondue en une phrase du socle — c'est le plus propre : un seul littéral court minimise le doublon avec
  `regles.md`, où vit désormais le détail opérationnel pour les postes gouvernés.
- **Répartition** : le détail opérationnel (où vit quoi, construction bornée détaillée) vit dans
  `regles.md` (postes avec paquet activé) ; le socle `AtelierChatService` est le **plancher universel**
  injecté pour tous.
- **Gateway-First / Provider-First** respectés (consigne de conduite, pas de moteur IA, réutilise
  `write_file`) ; aucune incohérence avec `ARCHITECTURE_CANONIQUE.md` (aucune table).
- **Décision (par défaut, flaguée) — budget `regles.md` (cap F-51)** : `regles.md` est injecté dans la
  consigne système à **chaque tour** et borné à `MAX_RULES_LENGTH = 8000` caractères (contrôlé par
  `GovernancePackageSeeder` + `GovernancePackageSeederTest.theRulesStayUnderTheLimit`). Le fichier était
  déjà à 7742 ; le volet additif l'aurait porté à 9242. Ce cap est **partagé** : il valide aussi les
  **règles fournies par l'utilisateur** (`GovernancePackageService`) et `GovernanceProfileSeeder` — le
  **relever loosenerait une limite produit hors périmètre**. Choix retenu : garder le volet **compact**
  et **récupérer de la place** en resserrant quelques passages **redondants / méta** de `regles.md`
  (meaning-preserving, aucune règle ni décision retirée, toutes les chaînes vérifiées par les tests
  conservées). Résultat : `regles.md` à **7972** caractères (< 8000). Le détail « déclencheur » (re-scan
  répété → proposer) vit dans le **socle universel** (que les postes gouvernés reçoivent aussi).
- Passages resserrés dans `regles.md` (redondance/méta, sans perte de règle) : phrase d'accumulation
  (§ La carte du poste), aparté « convention de chemin », rappel `STATE.md` brouillon (§ La promotion),
  justification méta du « second regard », aparté stylistique « listes à trois éléments » (§ livrables).
