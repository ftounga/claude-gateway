# Mini-spec — F-120 / SF-120-01 Doctrine « réponds d'abord, n'agis que sur demande »

## Identifiant

`F-120 / SF-120-01`

## Feature parente

`F-120` — Répondre sans agir : distinguer une question d'un ordre

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-120-01-doctrine-repondre-sans-agir`

---

## Objectif

> En une phrase : élever au rôle général de l'agent (RUNNER + SANDBOX) une doctrine de retenue non
> négociable — répondre à une question en texte, ne jamais déclencher une mutation non demandée —
> jusque-là cloisonnée à Radar/Pages, sans écraser la discipline d'investigation SF-119-02.

---

## Comportement attendu

### Cas nominal

La consigne système (`AtelierChatService.buildSystemPrompt`) porte, sur **les deux cibles**
(RUNNER et SANDBOX), un paragraphe de doctrine « Répondre d'abord, agir sur demande » ajouté en tête
(après le rôle, aux côtés de la discipline d'investigation SF-119-02 déjà présente), donc à l'abri de
la coupe `SYSTEM_MAX_CHARS`. La doctrine dit : une question n'est pas un ordre ; répondre en texte ;
lire pour répondre est permis, la mutation non demandée est proscrite ; sur une demande ambiguë,
proposer en une phrase et attendre.

La description de l'outil `set_plan` est corrigée : « n'établis ou ne mets à jour un plan que si
l'utilisateur te demande de planifier ou d'exécuter, ou si tu vas effectivement agir — pas parce que
le mot "plan" apparaît ».

Le `CLAUDE.md` du projet injecté verbatim est précédé d'un **préambule** cadrant : ces conventions
encadrent le travail *quand tu implémentes à la demande*, elles ne transforment pas une simple
question en ordre de dérouler une procédure.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `CLAUDE.md` absent (lecture optionnelle échoue) | Pas de section Conventions, donc pas de préambule associé ; la doctrine reste présente (elle ne dépend pas du CLAUDE.md). |
| Consigne système au-delà de `SYSTEM_MAX_CHARS` (40 000) | La doctrine, placée en tête, survit à la coupe ; seul le contenu de queue (catalogue de skills) est tronqué. |
| Gouvernance en panne (`governanceRules` rend `null`) | Repli passant inchangé (SF-119) ; la doctrine reste présente. |

---

## Critères d'acceptation

- [ ] La consigne système contient la doctrine de retenue sur une cible **SANDBOX** (substrings : « Répondre d'abord, agir sur demande », « Une question n'est pas un ordre », « Veux-tu que je le fasse »).
- [ ] La consigne système contient la doctrine de retenue sur une cible **RUNNER** (mêmes substrings).
- [ ] La description de `set_plan` déclarée au fournisseur contient « que si l'utilisateur te demande » et « pas parce que le mot ».
- [ ] Quand `CLAUDE.md` est présent, un préambule cadrant apparaît **avant** le contenu injecté (index du préambule < index de « Conventions du projet (CLAUDE.md) »).
- [ ] Non-régression : les substrings verrouillés par SF-119-02 restent présents (« Vérifie avant d'affirmer », « Ne généralise jamais à partir d'un seul exemple », « non concluant »), ainsi que le rôle et l'outillage annoncés.
- [ ] Aucune capacité IA réimplémentée : seul le texte du prompt et la description d'un outil changent (V1 gateway pure).

---

## Périmètre

### Hors scope (explicite)

- Tout changement de comportement de la boucle, de `buildTools` (au-delà de la description de `set_plan`), ou de l'exposition d'outils — c'est SF-120-02 (mode explicite).
- Toute UI / tout écran — SF-120-01 est zéro UI.
- Le classifieur d'intention heuristique — SF-120-03, mise en réserve (non retenue).
- Refonte du crochet de fin de tour (F-50/F-119).

---

## Contraintes de validation

| Champ | Obligatoire | Contrainte |
|-------|-------------|-----------|
| Texte de doctrine (`RESTRAINT_DOCTRINE`) | Oui | Constante Java, français, ton tutoiement cohérent avec l'existant, ajoutée sur les deux cibles, en tête. |
| Description `set_plan` | Oui | Reformulée pour conditionner le plan à la demande / à l'action effective. |
| Préambule CLAUDE.md (`GOVERNANCE_PREAMBLE`) | Oui | Ajouté immédiatement avant le contenu du CLAUDE.md injecté. |

---

## Technique

### Endpoint(s)

Aucun (changement interne au service).

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants impactés

- `AtelierChatService.buildSystemPrompt` — ajout de la doctrine (deux cibles) + préambule CLAUDE.md.
- `AtelierChatService.buildTools` — description de `set_plan`.
- Constantes : `RESTRAINT_DOCTRINE`, `GOVERNANCE_PREAMBLE`.

### Analyse transversale (préoccupations)

- **Auth / Principal** : non touché.
- **Contexte tenant** : non touché (isolation `user_id`/`workspace_id` inchangée, `requireOwned` en tête de boucle).
- **Plans / limites** : non touché.
- **Navigation / routing** : non touché (zéro UI).

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceSystemPromptTest` — la doctrine est présente sur un projet SANDBOX.
- [ ] `AtelierChatServiceSystemPromptTest` — la doctrine est présente sur un projet RUNNER.
- [ ] `AtelierChatServiceSystemPromptTest` — la description de `set_plan` conditionne le plan à la demande.
- [ ] `AtelierChatServiceSystemPromptTest` — le préambule encadre le CLAUDE.md injecté (ordre vérifié).
- [ ] Non-régression : les tests SF-119-02 existants (discipline d'investigation, contrats edit/write) restent verts.

### Tests d'intégration

Sans objet (aucun endpoint modifié). La consigne est observée là où elle compte : la requête reçue
par le fournisseur stub, comme les tests existants du même fichier.

### Isolation workspace

- [x] Non applicable — aucun nouvel accès aux données ; `requireOwned` (isolation `user_id`) reste en tête de la boucle, inchangé.

---

## Dépendances

### Subfeatures bloquantes

- F-119 / SF-119-02 — **Done** (a enrichi `buildSystemPrompt`) : SF-120-01 s'y intègre sans l'écraser.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- La doctrine et la discipline d'investigation (SF-119-02) sont **complémentaires** : l'une dit
  *quand* agir, l'autre *comment* vérifier quand on agit. Les deux cohabitent en tête de consigne.
- SF-120-01 ne restreint **aucun** outil : un agent en mode Agir garde toute sa panoplie ; la doctrine
  agit par le prompt. La restriction déterministe des outils est le sujet de SF-120-02.
