# Mini-spec — [F-119 / SF-119-03] La preuve survit à l'affirmation

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage : `docs/features/F-119/CADRAGE-F-119-justesse-de-l-agent.md` (Cause 3).

---

## Identifiant

`F-119 / SF-119-03`

## Feature parente

`F-119` — La justesse de l'agent : se tromper moins, se corriger moins

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-119-03-preuve-survit`

---

## Objectif

> En une phrase : garder le **couplage affirmation↔preuve** plus longtemps — élargir la fenêtre de
> rejeu des trajectoires d'outils, aligner l'extrait rejoué sur l'extrait vu **en direct** (la tête),
> et faire porter à la compaction un **digest structuré des résultats d'outils** au lieu du texte seul
> — pour que l'agent cesse de croire ses vieilles affirmations sans la donnée pour les réviser.

---

## Comportement attendu

### Cas nominal

1. **Fenêtre de rejeu élargie** : les traces d'outils des N derniers tours sont rejouées avec leurs
   `tool_result`. N passe de 5 à **12** (configurable `app.atelier.replayed-trace-turns`). Au-delà,
   rejeu en texte seul (inchangé).
2. **Cohérence tête/queue** : au rejeu, le résultat borné garde la **tête** (`AtelierToolTrace.
   boundResult`), le même extrait que l'affichage en direct (`bashOutcome`/`readOutcome` gardent le
   début). La borne `MAX_RESULT_CHARS` passe de 4 000 à 8 000, `MAX_TRACE_CHARS` de 40 000 à 60 000.
3. **Digest de compaction structuré** : le résumé (`AtelierCompactionService.renderForSummary`) inclut,
   pour chaque tour de l'agent, une ligne par appel d'outil — outil, cible (fichier/commande/requête)
   et **issue** (code de sortie, extrait de sortie, échec). La consigne de résumé est durcie
   (anti-invention explicite : ne citer aucune valeur/code/contenu absent).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `replayed-trace-turns` absent/nul/négatif | Retombe sur le défaut (12) |
| `replayed-trace-turns` déraisonnable (> 40) | Ramené au plafond lisible (40) |
| Trajectoire d'outils illisible (JSON tronqué/ancien) | Digest vide, rejeu en texte seul — jamais d'exception (comportement d'avant) |
| Tour d'agent **sans texte** mais avec des outils | Son digest est quand même rendu au résumé (il porte l'information à conserver) |
| Résultat plus long que `MAX_RESULT_CHARS` | Tête conservée + marqueur « … (fin tronquée) » ; sur une sortie très longue le code de sortie (en queue) peut sortir de la mémoire — le signal d'échec, lui, a été capté en direct (SF-119-01) |

---

## Critères d'acceptation

- [ ] Au-delà de l'ancienne fenêtre (5) et jusqu'à 12, un résultat d'outil reste rejoué avec sa trace.
- [ ] L'extrait rejoué d'un résultat tronqué est la **tête** (== extrait direct), plus la queue.
- [ ] La compaction produit un digest mentionnant les **outils** et leur **issue**, pas seulement le
      texte ; l'issue d'une commande (code de sortie) survit.
- [ ] `replayed-trace-turns` configurable, défaut 12, repli et plafond corrects.
- [ ] Aucun test existant de compaction/trace ne casse (le digest n'apparaît que pour un tour
      d'agent porteur d'une trace ; `AtelierTurnReport` — transcript d'affichage — n'est pas touché).

---

## Périmètre

### Hors scope (explicite)

- L'affichage/transcript (`AtelierTurnReport`) qui garde la **queue** pour l'écran : hors sujet, c'est
  la **mémoire de rejeu** (`AtelierToolTrace`) qu'on aligne sur le direct, pas l'affichage.
- La ré-escalade d'effort (SF-119-01), la discipline de prompt (SF-119-02), la sortie partielle des
  bash (SF-119-04), le suivi d'état de fichier (SF-119-05).

---

## Valeurs initiales / réglages

| Propriété | Clé env | Défaut | Règle |
|-----------|---------|--------|-------|
| `replayedTraceTurns` | `APP_ATELIER_REPLAYED_TRACE_TURNS` | `12` | Absent/nul/négatif ⇒ 12 ; > 40 ⇒ 40 |
| `MAX_RESULT_CHARS` (const.) | — | `8000` (était 4000) | Borne mémoire par résultat |
| `MAX_TRACE_CHARS` (const.) | — | `60000` (était 40000) | Borne mémoire par tour |

---

## Technique

### Composants impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `AtelierToolTrace` | `boundResult` → tête ; `TRUNCATION_MARK` en suffixe ; bornes relevées | Cohérence tête/queue |
| `AtelierChatService` | `replayedTraceTurns` (propriété) threadé dans `firstTracedIndex`/`replayableHistory` | Fenêtre configurable |
| `AtelierProperties` | `replayedTraceTurns` (fin du record) + défaut/plafond + compat ctor | |
| `AtelierCompactionService` | digest structuré dans `renderForSummary` ; consigne anti-invention durcie | Cœur Cause 3 |
| `application.yml` | clé `replayed-trace-turns` | |

### Endpoint(s) / Tables / Migration

Aucun. Mémoire de contexte de la boucle uniquement.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierToolTraceTest` — `boundResult` garde la tête (== direct), marqueur en fin, taille bornée.
- [ ] `AtelierChatServiceMemoryTest` — sur 15 tours, les 12 derniers sont rejoués avec trajectoire
      (call_3…call_14) ; au-delà texte seul.
- [ ] `AtelierPropertiesTest` — défaut 12, repli non positif, valeur honorée, plafond 40.
- [ ] `AtelierCompactionServiceTest` — le résumé inclut un digest structuré (outil + cible + issue),
      l'issue d'un bash (code de sortie) survit, un tour sans texte mais avec outils porte son digest,
      `toolDigest(null)`/JSON illisible ⇒ vide.

### Tests d'intégration / isolation

- [ ] Non applicable — aucune donnée nouvelle ; la lecture reste filtrée `user_id`+`workspace_id`
      (compaction et rejeu inchangés sur ce point).

---

## Préoccupations transversales

- **Plans / limites** : la fenêtre élargie et les bornes relevées **augmentent** le contexte rejoué —
  compensé par la compaction (F-117) qui borne déjà le volume total, et le cache de prompt qui relit
  au dixième du tarif. Composants concernés (vérifiés inchangés) : `AtelierCompactionService`
  (déclenche sur le texte rejoué ; le digest s'ajoute au texte résumé, borné par tour), plafond
  `maxTurnTokens` et budget `turnBudget` (bornes inchangées). Aucun nouveau gate.
- **Auth / tenant / navigation** : non.

---

## Notes et décisions

- **Décision par défaut** : fenêtre 12 (dans la fourchette 12–15 du cadrage), plafond 40 ; bornes
  char relevées de façon raisonnée (×2 et ×1,5). Réglable pour la fenêtre.
- **Tête au rejeu** : priorité à la cohérence avec le direct (le cadrage l'exige) ; le code de sortie
  en queue peut manquer sur une sortie énorme, mais le signal a déjà servi en direct (SF-119-01).
- **Digest** : borné par tour (`MAX_DIGEST_CHARS_PER_TURN`), une valeur par ligne (jamais un fichier
  entier) — un aide-mémoire structuré, pas la trace complète.
