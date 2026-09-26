# Mini-spec — F-121 / SF-121-12 — Prompt système structuré en sections stables + encadrement du CLAUDE.md + guidance de choix d'outil

## Identifiant

`F-121 / SF-121-12`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison). Écart de parité **Lot 2** du cadrage
`docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` §3 :

> **F-121-12** — **Prompt système plat** + CLAUDE.md injecté verbatim ; pas de guidance de choix
> d'outil. Structurer `buildSystemPrompt` en sections stables (Rôle · Investigation · Style · Outils ·
> Conventions projet), encadrer le CLAUDE.md (préambule SF-120-01), ajouter « quand `explore` vs
> `bash` vs `task` ».

## Statut

`done`

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-12-prompt-systeme-sections`

---

## Objectif

Donner au préfixe système de la boucle maison une **charpente de sections nommées et stables**,
**refermer** le `CLAUDE.md` injecté verbatim, et ajouter une **section « Choix des outils »** qui
départage `grep`/`glob`, `read_file`, `edit_file`/`multi_edit`/`write_file`, `explore`, `bash` et
`task` — le tout sans toucher une seule capacité IA et sans introduire la moindre volatilité dans le
préfixe caché (F-134).

---

## Contexte — pourquoi c'est un écart de parité

`AtelierChatService.buildSystemPrompt` a grossi par sédimentation : onze doctrines (SF-119-02,
SF-120-01, SF-121-03, SF-121-21, SF-125-01, SF-125-05, SF-126-01, SF-141-01/02, SF-120-02…) puis les
guides de volets, puis le savoir du client, le `CLAUDE.md`, les règles de gouvernance, l'état du sujet
et les skills, **concaténés à plat**. Trois conséquences mesurables :

1. **Pas de charpente.** Seuls `--- Environnement ---`, `--- Conventions du projet (CLAUDE.md) ---`,
   `--- Règles de gouvernance ---`, `--- Skills du projet ---` et les guides de volets portent un
   marqueur. Les huit doctrines de conduite, elles, se suivent sans séparateur : le modèle lit un mur
   de paragraphes impératifs sans savoir où finit la méthode et où commence le style. Claude Code
   structure son prompt en sections titrées ; c'est la différence corrigée ici.
2. **Le `CLAUDE.md` n'est pas refermé.** Le préambule de SF-120-01 l'**ouvre** (« ces conventions
   encadrent le travail quand tu IMPLÉMENTES ») et un marqueur l'annonce, mais **rien ne le clôt** :
   le contenu verbatim — souvent un manuel impératif plein de « REFUS si… » — coule directement dans
   la section suivante. Un `CLAUDE.md` qui se termine par une consigne ouverte est indiscernable
   d'une consigne de la passerelle. « Encadrer » veut dire deux bornes, pas une.
3. **Aucune guidance de choix d'outil.** La panoplie est riche (jusqu'à `grep`, `glob`, `read_file`,
   `write_file`, `edit_file`, `multi_edit`, `bash`, `explore`, `task`) mais chaque outil ne se décrit
   que **lui-même**. La seule doctrine comparative existante — `task` vs `explore` vs `bash`
   (SF-150-04) — vit **dans la description de l'outil `task`**, donc n'existe pas du tout quand `task`
   n'est pas déclaré (cible SANDBOX, ou `max-delegations: 0`), et ne dit rien de `grep` vs `bash`,
   ni de `edit_file` vs `write_file`.

---

## Comportement attendu

### Cas nominal

`buildSystemPrompt` rend la **même matière** qu'aujourd'hui, dans le **même ordre**, mais découpée par
des bannières au vocabulaire **déjà en place** (`--- Titre ---`), plus une section neuve :

| # | Section | Contenu | Nouveau ? |
|---|---------|---------|-----------|
| 1 | (amorce de rôle, **sans bannière**) | rôle générique ou profil métier actif (SF-148-02) | non |
| 2 | `--- Environnement ---` | date, cwd, OS, shell, instantané git (SF-121-21) | non |
| 3 | `--- Méthode de travail ---` | discipline d'investigation (SF-119-02) + doctrine de retenue (SF-120-01) | **bannière** |
| 4 | `--- Style de réponse ---` | style terminal (SF-121-03), carte silencieuse (SF-125-01), balisage de l'essentiel (SF-126-01), conseil qui tranche (SF-125-05), annonce de destination (SF-141-01), aiguillage racine (SF-141-02), consigne de mode (SF-120-02) | **bannière** |
| 5 | `--- Choix des outils ---` | **guidance neuve** (voir ci-dessous), puis les notices et guides de volets (Teams, Radar, pages, présentations, images, diagrammes, decks, actions) | **bannière + contenu** |
| 6 | (savoir du client — il porte déjà son propre titre) | sommaire de carte (SF-136-02) | non |
| 7 | `--- Conventions du projet (CLAUDE.md) ---` … `--- Fin des conventions du projet (CLAUDE.md) ---` | préambule SF-120-01 + contenu verbatim, **refermé** | **borne de fin** |
| 8 | `--- Règles de gouvernance … ---` | paquet de gouvernance | non |
| 9 | `--- État courant du sujet ---`, `--- Skills du projet … ---`, primauté de l'outil | inchangés | non |

**L'amorce de rôle reste les tout premiers octets du prompt** (SF-148-02 : le bon cadre se lit dès la
première phrase ; un profil métier actif la remplace). Aucune bannière ne la précède.

#### La section « Choix des outils »

Texte **fixe**, assemblé à partir de fragments **constants**, dont la composition ne dépend que de
propriétés **stables** du tour (cible du workspace, `max-delegations`) — donc byte-identique d'un tour
à l'autre pour un même workspace.

Lignes toujours présentes (les outils concernés sont déclarés sur les **deux** cibles) :

- **chercher** un motif → `grep` (regex, `include`), **trouver** un fichier par nom → `glob` ;
- **lire** avant d'utiliser → `read_file` ;
- **modifier** → `edit_file` (un passage) ou `multi_edit` (plusieurs sur le même fichier, atomique) ;
  `write_file` seulement pour créer ou tout réécrire ;
- **grouper** dans le même tour les appels indépendants.

Lignes **conditionnelles**, ajoutées seulement si l'outil est réellement déclaré (règle SF-39-05 :
annoncer un outil qui n'existe pas ne produit que des appels perdus) :

| Condition | Ligne ajoutée |
|---|---|
| `maxDelegations > 0` (⇒ `explore` déclaré) | `explore` pour LIRE beaucoup sans garder le détail (lecture seule, seule la synthèse revient) |
| cible `RUNNER` (⇒ `bash` déclaré) | `bash` pour EXÉCUTER (build, tests, git) ; pour **chercher**, préférer `grep`/`glob` — plus rapide et indépendant du shell |
| cible `SANDBOX` (⇒ `list_files`/`search_files` déclarés, pas de `bash`) | `list_files` pour lister ; `search_files` = sous-chaîne simple, préférer `grep` |
| cible `RUNNER` **et** `maxDelegations > 0` (⇒ `task` déclaré) | `task` pour ÉCRIRE/EXÉCUTER une sous-tâche isolée (worktree git à part) ; sinon agir soi-même dans la boucle principale |

La doctrine `task`/`explore`/`bash` de SF-150-04 **reste** dans la description de l'outil `task` : elle
y est lue au moment de choisir *cet* outil. La section la **résume** au niveau du prompt pour qu'un
arbitrage existe même là où `task` n'est pas déclaré — sans la contredire (mêmes verbes : `task`
écrit/exécute à l'écart, `explore` lit, `bash` agit ici).

### Cas d'erreur

Pas d'endpoint, pas d'entrée utilisateur : les cas d'erreur sont ceux du prompt lui-même.

| Situation | Comportement attendu |
|-----------|----------------------|
| `CLAUDE.md` absent / illisible | Ni préambule, ni bornes d'ouverture **ni de fermeture** — comme aujourd'hui, la section n'existe tout simplement pas (repli passant) |
| `CLAUDE.md` présent mais vide | Même traitement qu'aujourd'hui (`promptFile` rend une chaîne) : bornes posées autour d'un contenu vide, jamais d'exception |
| Aucun volet ouvert | La section `--- Choix des outils ---` existe quand même : sa partie fixe parle d'outils toujours déclarés |
| Prompt plus long que `SYSTEM_MAX_CHARS` (40 000) | Coupe inchangée, en queue ; les sections 1→5 (rôle, environnement, méthode, style, outils) sont en tête et y survivent |
| Profil métier actif (F-138) | L'amorce reste remplacée par la phrase du profil et reste les premiers octets ; les bannières suivent |

---

## Critères d'acceptation

1. Le prompt **commence** toujours par l'amorce de rôle (ou la phrase du profil actif) : aucune
   bannière ne la précède.
2. Le prompt contient, **dans cet ordre**, `--- Environnement ---`, `--- Méthode de travail ---`,
   `--- Style de réponse ---`, `--- Choix des outils ---`.
3. `--- Méthode de travail ---` précède immédiatement la discipline d'investigation ;
   `--- Style de réponse ---` précède immédiatement le style terminal.
4. Quand un `CLAUDE.md` est injecté, le prompt contient **`--- Fin des conventions du projet
   (CLAUDE.md) ---`**, situé **après** le contenu du fichier et **avant** les règles de gouvernance.
5. Quand aucun `CLAUDE.md` n'est lisible, **ni** la borne d'ouverture **ni** la borne de fermeture
   n'apparaissent.
6. La section `--- Choix des outils ---` cite `grep`, `glob`, `read_file`, `edit_file`, `multi_edit`
   et `write_file` sur les deux cibles.
7. Sur cible `RUNNER`, elle cite `bash` et ne cite ni `list_files` ni `search_files` ;
   sur cible `SANDBOX`, elle cite `list_files`/`search_files` et **ne cite pas** `bash`.
8. Sur cible `RUNNER` avec délégation autorisée, elle cite `task` et `explore` ; avec
   `max-delegations: 0`, elle ne cite **ni** `task` **ni** `explore`.
9. Sur cible `SANDBOX`, elle ne cite **jamais** `task` (l'outil n'y est pas déclaré), même avec
   délégation autorisée.
10. Deux constructions successives du prompt pour le même workspace rendent des chaînes **égales**
    (préfixe stable ⇒ cache F-134 préservé) ; aucun élément volatil (compte, heure, identifiant)
    n'entre dans les sections ajoutées.
11. Aucune capacité IA n'est réimplémentée, aucun appel fournisseur ajouté : la subfeature est
    **prompt-only**.

---

## Plan de test minimal

### Tests unitaires (`AtelierChatServiceSystemPromptTest`, cible SANDBOX)

| # | Test | Vérifie |
|---|------|---------|
| T1 | `sectionsAppearInStableOrder` | les quatre bannières présentes dans l'ordre attendu |
| T2 | `roleStaysFirstBeforeAnySection` | le prompt `startsWith` l'amorce de rôle ; `--- Méthode de travail ---` vient après |
| T3 | `projectConventionsAreClosedByAnEndMarker` | avec un `CLAUDE.md`, la borne de fin existe, après le contenu et avant les règles de gouvernance |
| T4 | `noEndMarkerWhenNoProjectConventions` | sans `CLAUDE.md` lisible, aucune des deux bornes |
| T5 | `toolChoiceSectionNamesSandboxTools` | `grep`/`glob`/`read_file`/`edit_file`/`multi_edit`/`list_files`/`search_files` cités, `bash` et `task` absents |
| T6 | `toolChoiceSectionIsStableAcrossBuilds` | deux prompts successifs identiques |

### Tests unitaires (cible RUNNER)

| # | Test | Vérifie |
|---|------|---------|
| T7 | `toolChoiceSectionNamesRunnerTools` | `bash` cité, `grep` préféré à `bash` pour chercher, `list_files`/`search_files` absents |
| T8 | `toolChoiceSectionAnnouncesTaskOnRunnerWithDelegation` | `task` et `explore` cités |
| T9 | `toolChoiceSectionOmitsTaskAndExploreWithoutDelegation` | `max-delegations: 0` ⇒ ni `task` ni `explore` |

### Tests de non-régression

- `AtelierChatServiceGovernanceRulesTest` (le rôle reste en tête, profil actif compris) ;
- `AtelierChatServiceModeTest`, `AtelierChatServiceTeamsToolsTest`,
  `AtelierChatServiceTeamsReadFailureTest`, `AtelierChatServiceRadarToolsTest`,
  `AtelierChatServicePageToolTest`, `AtelierChatServiceHostKnowledgeTest` (égalité de deux
  constructions), `AtelierChatServiceRepoIndexTest` ;
- suite backend complète (`mvn -pl backend test`).

### Isolation `user_id`

Aucun nouvel accès aux données. Toutes les lectures du prompt (CLAUDE.md, arborescence, skills,
gouvernance, savoir, état du sujet) passent **inchangées** par les chemins existants, tous portés par
`userId` + `workspace` (`promptFile`, `requireOwned`). La subfeature n'ajoute **aucune** requête.

---

## Tables / endpoints / composants impactés

| Type | Élément | Impact |
|------|---------|--------|
| Table | — | **aucune** |
| Migration Liquibase | — | **aucune** |
| Endpoint | — | **aucun** (la consigne système n'est pas exposée) |
| Backend | `AtelierChatService` (`buildSystemPrompt`, constantes de section + `toolChoiceSection`) | modifié |
| Frontend | — | **aucun** (pas d'écran : la consigne système n'est jamais rendue à l'utilisateur) |
| Protocole runner | — | **aucun changement** (aucun outil ajouté ni modifié) |
| Composant cluster | — | **aucun** |

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Analyse d'impact |
|---|---|---|
| Auth / Principal | **non** | aucune signature d'auth touchée ; `buildSystemPrompt(userId, workspace, mode)` inchangée |
| Contexte tenant | **non** | aucun nouvel accès données ; les lectures existantes restent portées par `userId`+`workspace` |
| Plans / limites | **non** | `maxDelegations` est **lu** (propriété déjà injectée) pour décider d'annoncer `task`/`explore` ; aucun gate, aucun quota modifié |
| Navigation / routing | **non** | aucun frontend |

**Cache de prompt (F-134)** — préoccupation propre à ce fichier : les sections ajoutées sont des
**littéraux** et leur composition ne dépend que de propriétés stables (cible du workspace,
`max-delegations`). Rien de volatil (ni date, ni compteur, ni identifiant de tour). Le préfixe change
**une fois**, à la livraison ; il redevient byte-stable ensuite. Critère d'acceptation 10 le garde.

---

## Hors périmètre

- **Réécrire** le contenu des doctrines existantes : elles sont reprises **à l'octet près**, seules
  des bannières les séparent. Toute réécriture relèverait des SF qui les ont posées.
- **Déplacer** un bloc dans le prompt : l'ordre actuel est conservé intégralement.
- **Retirer** la doctrine `task`/`explore`/`bash` de la description de l'outil `task` (SF-150-04) :
  elle reste, la section la résume.
- Traces / métriques sur la composition du prompt (F-121-00, dans F-119-03).
- Estimateur de tokens (F-121-18), pilotage des steers (F-121-11), gabarit de compaction (F-121-09).
- Toute capacité IA : **V1 = gateway pure**, aucun OCR/RAG/pgvector, aucun moteur maison.
