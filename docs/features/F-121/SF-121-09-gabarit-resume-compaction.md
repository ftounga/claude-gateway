# Mini-spec — F-121 / SF-121-09 — Gabarit sectionné du résumé de compaction

## Identifiant

`F-121 / SF-121-09`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison). Écart **Lot 2 / P2** du cadrage
`docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` §3 :

> **F-121-09** — **Compaction en prose libre** (pas de gabarit). Gabarit sectionné du résumé
> (objectif / fichiers modifiés / décisions / état / prochaines étapes) dans `SUMMARY_SYSTEM_PROMPT`
> (`:57-63`). Complète le digest d'outils de SF-119-03.

## Statut

`done`

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-09-gabarit-resume-compaction`

---

## Objectif

Faire produire au résumé de compaction un **gabarit sectionné stable** (Objectif · Fichiers ·
Décisions et faits établis · État courant · Prochaines étapes) au lieu d'une prose libre, pour que
la reprise après compaction retrouve les mêmes rubriques à la même place, tour après tour.

---

## Contexte — pourquoi c'est un écart de parité

La compaction (F-117 / SF-117-01) demande aujourd'hui « un résumé FACTUEL et COMPACT » en énumérant
en une phrase ce qui doit survivre (`AtelierCompactionService.java:57-68`). Le modèle rend alors de
la **prose libre** : l'ordre des informations, leur présence et leur granularité varient d'une
compaction à l'autre. Deux conséquences observées :

1. **Reprise instable.** Le résumé est réinjecté en tête du rejeu (`summaryPrefix`, préfixe stable du
   cache F-134). Un bloc dont la forme change à chaque compaction oblige le modèle à le relire
   comme un texte quelconque, au lieu de pointer directement « où en est-on / que reste-t-il ».
2. **Perte silencieuse.** Sans rubrique dédiée, « ce qui reste à faire » est la première chose que le
   modèle sacrifie quand il compresse — précisément l'information dont la reprise a besoin.

Claude Code compacte sur un gabarit sectionné fixe. La **compaction est incrémentale** ici (on résume
le résumé précédent + les tours accumulés) : sans gabarit, chaque passe reformule le texte de la
précédente et l'information dérive ; avec gabarit, chaque passe **fusionne section par section**.

C'est un réglage de **prompt de la boucle** : aucune capacité IA n'est réimplémentée (Provider-First),
l'appel passe par `AiAgentProvider` comme avant (Provider Independence).

---

## Comportement attendu

### Cas nominal

| | Aujourd'hui | Après SF-121-09 |
|---|---|---|
| Consigne de résumé | énumération en prose de ce qui doit survivre | **gabarit imposé** : 5 sections titrées, dans cet ordre |
| Sortie du modèle | prose libre | `## Objectif` · `## Fichiers` · `## Décisions et faits établis` · `## État courant` · `## Prochaines étapes` |
| Section sans contenu | (n/a) | la section est **conservée** avec `—` (ni invention, ni structure qui saute) |
| Compaction incrémentale | « Résumé précédent : … » puis la conversation | idem + consigne explicite de **fusionner section par section**, sans imbriquer un résumé dans un résumé |
| Résumé hors gabarit | (n/a) | **accepté tel quel** (best-effort) + journal `debug`, jamais d'échec de tour |

Les garde-fous existants sont **repris mot pour mot** : pas de préambule ni de conclusion, appui sur
les lignes « outils utilisés » (`·`) du digest SF-119-03, commandes **avec leur issue** (réussie /
échouée, code de sortie), et l'interdit d'invention (« n'invente rien, si une information manque, ne
la mentionne pas »).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le modèle rend un résumé **sans** les titres du gabarit | Résumé **conservé** (best-effort, un résumé imparfait vaut mieux qu'un fil non compacté) ; trace `debug`, aucune exception |
| Le modèle rend un résumé **vide / blanc** | Inchangé : fil laissé intact, `CompactionOutcome.NONE` (comportement F-117) |
| L'appel de résumé échoue (fournisseur indisponible) | Inchangé : best-effort, rien n'est écrit, le filet réactif SF-117-02 prend le relais |
| Résumé précédent lui-même en prose libre (fil compacté avant cette SF) | Fusionné dans le gabarit à la compaction suivante — aucune migration, convergence naturelle |

---

## Critères d'acceptation

- [x] CA1 — `SUMMARY_SYSTEM_PROMPT` impose les **cinq** sections, dans l'ordre Objectif → Fichiers → Décisions et faits établis → État courant → Prochaines étapes.
- [x] CA2 — La consigne dit explicitement de **conserver une section vide** avec `—` plutôt que de la supprimer.
- [x] CA3 — Les garde-fous F-117/SF-119-03 sont préservés : pas de préambule/conclusion, appui sur les lignes `·`, issue des commandes, interdit d'invention.
- [x] CA4 — En compaction **incrémentale** (résumé précédent non vide), le texte soumis demande la **fusion section par section** et interdit d'imbriquer un résumé dans un résumé.
- [x] CA5 — Un résumé rendu **hors gabarit** est tout de même écrit (best-effort) ; la compaction reste `compacted = true`.
- [x] CA6 — Non-régression F-117 : seuil, frontière, tours gardés entiers, `CompactionOutcome`, résumé vide ⇒ `NONE`, échec fournisseur ⇒ `NONE`.
- [x] CA7 — Isolation : aucun accès données nouveau ; la lecture des messages reste filtrée `workspace_id` + `user_id`.
- [x] CA8 — Provider Independence : l'appel reste `AiAgentProvider.nextTurn` ; aucun modèle ni fournisseur en dur ajouté.
- [x] CA9 — Cache F-134 : la **consigne système de la boucle principale** est strictement inchangée (seule celle de l'appel dédié de compaction bouge, qui n'est pas dans le préfixe caché du tour).

---

## Périmètre

### Hors scope (explicite)

- Le **seuil** de compaction, la fenêtre de tours gardés, l'estimateur (`estimateReplayTokens`) : inchangés.
- Le **digest d'outils** (SF-119-03) : réutilisé tel quel, pas retouché.
- Toute **validation bloquante** du gabarit (rejet d'un résumé non conforme, reformatage forcé, second appel modèle) : contraire au caractère best-effort de la compaction et coûteux — voir D3.
- **Affichage** du résumé à l'utilisateur : aucun écran ne l'expose aujourd'hui ; hors sujet ici.
- Frontend, endpoints, tables, migration, protocole runner : **aucun**.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune (`workspace.chat_thread_summary` existe déjà, type et sémantique inchangés).

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — aucun changement de schéma.

### Composants Angular

Aucun.

### Composants backend impactés

| Fichier | Changement |
|---------|-----------|
| `atelier/AtelierCompactionService.java` | `SUMMARY_SECTIONS` (les 5 titres) ; `SUMMARY_SYSTEM_PROMPT` réécrit autour du gabarit ; `renderForSummary` ajoute la consigne de fusion quand un résumé précédent existe ; `looksSectioned` (observation, non bloquante) journalisée en `debug` |

### Contraintes de validation

| Champ | Contrainte | Source |
|-------|-----------|--------|
| Sections du gabarit | 5, fixes, ordonnées, titrées `## ` | tranché ici (D1) |
| Section vide | `—` (tiret cadratin), section conservée | tranché ici (D2) |
| Conformité du résumé | **non contraignante** (observation `debug`) | tranché ici (D3) |
| Longueur du résumé | inchangée — bornée par le modèle et le budget de l'appel dédié | F-117 / SF-117-01 |

---

## Plan de test

### Tests unitaires — `AtelierCompactionServiceTest`

- [x] La consigne de résumé énumère les cinq sections dans l'ordre (CA1).
- [x] La consigne impose `—` pour une section vide et conserve les garde-fous (pas d'invention, lignes `·`, issue des commandes) (CA2, CA3).
- [x] `renderForSummary` **sans** résumé précédent : aucune consigne de fusion parasite.
- [x] `renderForSummary` **avec** résumé précédent : consigne de fusion section par section présente (CA4).
- [x] Un résumé rendu hors gabarit est écrit quand même, `compacted = true` (CA5).
- [x] Non-régression : compaction au-dessus du seuil, frontière avancée, tours récents gardés, appel sans outils, `system()` = consigne (CA6).
- [x] Non-régression : sous le seuil / rien d'ancien / résumé vide / échec fournisseur ⇒ `NONE` (CA6).

### Tests d'intégration (boucle) — `AtelierChatServiceCompactionTest`

- [x] Non-régression : le résumé est réinjecté en tête du rejeu derrière `SUMMARY_MARKER`, le fil compacté part avec résumé + tours récents (suite existante, inchangée).

### Isolation utilisateur

- [x] Applicable — `replayable(userId, workspace)` reste filtré `workspace_id` + `user_id` ; aucune requête ajoutée, aucun élargissement de portée. Couvert par la suite existante (le dépôt est sollicité avec le couple).

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants vérifiés |
|--------------|-----------|---------------------|
| Auth / Principal | Non | Aucun changement d'authentification ni de Principal |
| Contexte tenant | Non | `AtelierCompactionService.replayable` (couple `(workspaceId, userId)` inchangé) ; `requireOwned` reste en amont dans `AtelierChatService` |
| Plans / limites | Non | `CompactionOutcome` inchangé : la consommation de l'appel de résumé est toujours agrégée aux compteurs du tour, aucun chemin de quota séparé |
| Navigation / routing | Non | Aucun frontend |
| **Prompts / cache de prompt (F-134)** | **Oui** | Consignes revues une par une : `AtelierChatService.buildSystemPrompt` (**inchangée**), `AtelierExploration` (**inchangée**), `AtelierCompactionService.SUMMARY_SYSTEM_PROMPT` (**seule modifiée** — consigne d'un appel **dédié**, hors préfixe caché du tour, donc aucun cache invalidé). `SUMMARY_MARKER`, lui, est inchangé : le préfixe de rejeu garde son libellé. |

---

## Dépendances

### Subfeatures bloquantes

- `SF-117-01` (compaction automatique) — done
- `SF-117-02` (filet réactif) — done
- `SF-119-03` (digest d'outils en compaction) — done

### Questions ouvertes impactées

Aucune (`docs/OPEN_QUESTIONS.md` non touché).

---

## Notes et décisions

- **D1 — Cinq sections, titrées en markdown.** Exactement celles du cadrage. Titres `## ` plutôt
  qu'une liste à puces : le bloc est réinjecté dans un message utilisateur derrière `SUMMARY_MARKER`,
  et un titre reste un repère non ambigu même concaténé à d'autres textes. « Décisions et faits
  établis » fusionne décisions **et** issues de commandes : les séparer produisait deux sections
  redondantes, le digest `·` alimentant les deux.
- **D2 — Section vide conservée avec `—`.** Une structure constante vaut mieux qu'une structure
  variable : le modèle qui reprend sait à la même place qu'il n'y a rien, au lieu de se demander si
  la section a été omise ou perdue. Et `—` ne peut pas être lu comme un fait inventé.
- **D3 — Aucune validation bloquante du gabarit.** Rejeter un résumé non conforme reviendrait à ne
  pas compacter un fil qui déborde — le remède serait pire que le mal, alors que la compaction est
  best-effort par conception (F-117). Un second appel de reformatage doublerait le coût de chaque
  compaction pour un gain cosmétique. On **observe** donc (journal `debug`, sans contenu) et on écrit
  le résumé tel quel. *Alternative écartée* : refus + réessai ; *réversible* (la garde reste
  ajoutable si l'observation montre un vrai taux de non-conformité).
- **D4 — La fusion incrémentale est dite dans le message, pas dans la consigne système.** La consigne
  système de l'appel de compaction doit rester **stable** (préfixe cachable côté fournisseur) ; la
  phrase de fusion ne concerne que les compactions qui **ont** un résumé précédent — elle dépend donc
  de l'entrée et va dans le message, conformément à la règle « rien de volatil dans le préfixe stable ».
- **Gateway-First / Provider-First** : on règle un prompt de la boucle, on ne réimplémente aucune
  capacité du modèle. **Provider Independence** : l'appel reste `AiAgentProvider.nextTurn`.
