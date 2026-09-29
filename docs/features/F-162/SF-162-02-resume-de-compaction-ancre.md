# Mini-spec — F-162 / SF-162-02 — Le résumé de compaction **ancré**

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage de référence (fait foi) : `docs/features/F-162/CADRAGE-F-162-rappel-a-la-demande.md`.
> SF précédente (livrée) : `docs/features/F-162/SF-162-01-outil-recall.md` — l'outil `recall(query)`.

---

## Identifiant

`F-162 / SF-162-02`

## Feature parente

`F-162` — Le rappel à la demande de l'historique (se souvenir sans tout rejouer)

## Statut

`ready`

## Date de création

2026-09-29

## Branche Git

`feat/SF-162-02-resume-ancre`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire évoluer `AtelierCompactionService` pour que le résumé de compaction porte des **ancres**
(numéro de **tour d'origine** cohérent avec `recall`, + **termes distinctifs** retrouvables en
mot-clé), afin que le modèle sache **quoi** rappeler et **où** le retrouver via l'outil `recall`
(SF-162-01) — sans gonfler le résumé ni casser le cache de prompt.

---

## Comportement attendu

### Cas nominal

1. La compaction se déclenche comme aujourd'hui (auto au seuil 120 K estimés, ou filet réactif
   `compactNow` sur un 400). Rien ne change au déclenchement, au bornage (garde 6 messages récents),
   ni au best-effort.
2. Avant l'appel de synthèse, chaque tour ancien rendu au modèle (`renderForSummary`) est **préfixé
   de son numéro de tour d'origine** `[tour N]`. Ce numéro est **le même que celui remonté par
   `recall`** : `N` = nombre de messages `USER` du fil dont `createdAt <= createdAt` du message
   (compté sur **tout le fil durable**, pas seulement depuis la frontière). La compaction étant
   incrémentale (frontière qui avance), un **offset de base** (nombre de tours `USER` antérieurs à la
   fenêtre rejouée) est calculé une fois, puis un compteur local le prolonge.
3. La **consigne de synthèse** (`SUMMARY_SYSTEM_PROMPT`, système de l'appel **dédié**) exige que
   chaque décision / fait / fichier / consigne retenu porte **son ou ses numéros de tour d'origine
   `(tour N)`** et **conserve les termes distinctifs** (noms, identifiants, chemins) qui permettront
   de le retrouver par `recall`. La consigne reste **bornée** : c'est un résumé, pas une copie.
4. Le **libellé du bloc de résumé** injecté au rejeu (`SUMMARY_MARKER`, préfixe stable) rappelle au
   modèle : « chaque point renvoie à son tour d'origine ; pour un détail non listé ici, utilise
   l'outil `recall` ». Ce rappel n'existe **pas encore** dans la consigne système principale (vérifié :
   SF-162-01 n'a ajouté qu'une **description d'outil**, pas de phrase système) → pas de duplication.
5. Le résumé ancré remplace, comme avant, les tours anciens dans le contexte vif ; l'affichage garde
   tout, la frontière `chat_thread_started_at` avance, le marqueur de repli manuel reste intact.

### Cas d'erreur / limites

| Situation | Comportement attendu |
|-----------|---------------------|
| Appel de synthèse en échec (fournisseur indisponible) | Best-effort inchangé : rien écrit, fil complet, filet réactif prend le relais |
| Résumé rendu sans ancre (`(tour N)` absent) | **Observation seule**, jamais un refus : le résumé est conservé tel quel (comme le gabarit, F-121/SF-121-09 D3) — ne pas compacter un fil qui déborde serait pire |
| Fenêtre rejouée démarrant sur un message `ASSISTANT` | Le tour affiché = dernier tour `USER` connu (= offset de base), cohérent avec `recall` |
| Première compaction (frontière nulle) | Offset de base = 0 ; les tours sont numérotés depuis 1 |

---

## Critères d'acceptation

- [ ] Le texte rendu au modèle pour synthèse (`renderForSummary`) préfixe **chaque tour** de son
      numéro d'origine `[tour N]`.
- [ ] La numérotation est **cohérente avec `recall`** : `N` = nombre de messages `USER` du fil avec
      `createdAt <= createdAt` du message, offset de base compris (test dédié sur une compaction
      incrémentale : les tours de la 2ᵉ passe sont numérotés à la suite de la 1ʳᵉ, pas remis à 1).
- [ ] `SUMMARY_SYSTEM_PROMPT` **exige** les ancres (numéro de tour + termes distinctifs) tout en
      restant bornée (« reste un résumé », pas de recopie).
- [ ] Les **garde-fous existants** de la consigne survivent mot pour mot : cinq sections dans l'ordre,
      « pas de préambule, pas de conclusion », « N'INVENTE RIEN », « LEUR ISSUE », « Garde TOUJOURS
      les cinq sections », « — », « · ».
- [ ] `SUMMARY_MARKER` rappelle l'usage de `recall` pour un détail non listé (une seule fois ;
      pas de duplication avec une phrase système existante — il n'y en a pas).
- [ ] **Non-régression compaction** : le déclenchement (seuil 120 K, garde 6, filet `compactNow`),
      l'estimateur, le best-effort et l'avance de frontière sont **inchangés** ; tous les tests
      existants de `AtelierCompactionServiceTest` et `AtelierChatServiceCompactionTest` restent verts.
- [ ] **Cache de prompt préservé** : le résumé reste un **préfixe stable** (numéros de tour = faits
      historiques immuables ; `SUMMARY_MARKER` = constante). Rien de volatil introduit.
- [ ] **Isolation** : l'offset de base est calculé par une requête filtrée `workspace_id` **ET**
      `user_id`.

---

## Périmètre

### Hors scope (explicite)

- **Aucune visibilité UI** (barre de progression / marqueur / indicateur `recall`) → SF-162-03.
- **Pas de bouton « compacter maintenant »** (SF-162-04), pas de filet utilisateur (SF-162-05).
- **Pas d'embeddings / sémantique** (SF-162-06).
- **Pas de modification de `recall`** lui-même (SF-162-01 livré) ni de sa requête.
- **Pas de nouveau réglage `@ConfigurationProperties`** : aucune modification de
  `AtelierCompactionProperties` (piège des deux constructeurs).

---

## Valeurs initiales

Aucune entité, aucune table, aucune colonne, aucune migration.

| Réglage | Valeur | Règle |
|---------|--------|-------|
| Format d'ancre dans le rendu | `[tour N] ` en préfixe de tour | constante de forme dans `renderForSummary` |
| Numérotation | `USER` count `createdAt <=` (offset de base + compteur local) | cohérente avec `recall` |

> **Décision** : rien n'est externalisé en configuration (le format et la numérotation sont du code
> de service). Motif : ne pas rouvrir le piège « deux constructeurs cassent le contexte Spring » sur
> le record `@ConfigurationProperties` (cf. SF-162-01, même décision).

---

## Technique

### Endpoint(s)

Aucun endpoint HTTP. Tout se joue dans l'appel de synthèse **dédié** de `AtelierCompactionService`.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `atelier_messages` | SELECT (COUNT) | Offset de base : nombre de messages `USER` antérieurs à la fenêtre rejouée, filtré `workspace_id` + `user_id` |

### Migration Liquibase

- [x] Non applicable — aucune table ni colonne nouvelle.

### Composants Angular

Aucun (backend seul ; la visibilité UI est SF-162-03).

### Points de câblage

- **`AtelierMessageRepository`** : ajout d'une requête dérivée
  `countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThan(workspaceId, userId, role, createdAt)`
  (offset de base ; symétrique du `...LessThanEqual` déjà utilisé par `recall`). Filtrée
  `workspace_id` + `user_id` (isolation).
- **`AtelierCompactionService.doCompact(...)`** : reçoit `userId`, calcule `baseTurns` une fois
  (sur `replayable.get(0).getCreatedAt()`), le passe à `summarize` → `renderForSummary`.
- **`AtelierCompactionService.renderForSummary(...)`** : surcharge à 3 arguments
  (`previousSummary`, `old`, `baseTurns`) qui préfixe chaque tour de `[tour N]` ; la surcharge à
  2 arguments existante est conservée (délègue avec `baseTurns = 0`, comme `estimateReplayTokens`).
- **`SUMMARY_SYSTEM_PROMPT`** : ajout de l'exigence d'ancrage (numéro de tour + termes distinctifs),
  garde-fous existants conservés.
- **`SUMMARY_MARKER`** : ajout du rappel « pour un détail non listé, utilise `recall` ».

---

## Plan de test

### Tests unitaires (service)

- [ ] `renderForSummary(null, old, 0)` : chaque tour préfixé `[tour N]`, numéros croissants
      (USER incrémente, ASSISTANT porte le dernier tour USER).
- [ ] `renderForSummary(previous, old, baseTurns)` : les tours sont numérotés **à partir de
      `baseTurns + 1`** (compaction incrémentale — pas de remise à 1).
- [ ] Non-régression du digest d'outils : `[tour N] ASSISTANT : …` + lignes `· read_file …`
      inchangées (les assertions existantes `contains("ASSISTANT : …")` restent vraies).
- [ ] `SUMMARY_SYSTEM_PROMPT` exige les ancres (contient « tour » / « (tour N) » et « distinctif »
      ou équivalent) **et** conserve les garde-fous existants (assertions existantes intactes).
- [ ] `SUMMARY_MARKER` contient un rappel de l'outil `recall`.
- [ ] Bout en bout : après compaction, le texte soumis à la synthèse contient au moins un `[tour `.

### Tests d'intégration / non-régression

- [ ] `AtelierCompactionServiceTest` : tous les tests existants restent verts (déclenchement,
      best-effort, incrémental, estimateur, gabarit).
- [ ] `AtelierChatServiceCompactionTest` : rejeu résumé + 2 récents inchangé (`SUMMARY_MARKER`
      toujours présent dans le préfixe).

### Isolation workspace / utilisateur

- [x] Applicable — l'offset de base est calculé par une requête filtrée `workspace_id` **ET**
      `user_id`. Câblage vérifié (le service passe le `workspace_id` et le `user_id` du tour).

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés |
|--------------|-------------|---------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non (réutilise `user_id` + `workspace_id` déjà résolus en amont par `AtelierChatService`) | Requête d'offset filtrée `workspace_id` + `user_id` |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |

---

## Dépendances

### Subfeatures bloquantes

- **SF-162-01** (livrée) : fournit la numérotation de tour de référence (`recall`) et le comptage
  `USER` dans `AtelierMessageRepository` que cette SF prolonge.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Cohérence de numérotation** : `recall` étiquette un extrait « tour N » avec
  `countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThanEqual(ws, user, "USER", createdAt)`. La
  compaction réutilise **exactement** cette notion : offset de base
  (`...CreatedAtLessThan` sur le début de la fenêtre) + compteur local qui incrémente sur chaque
  `USER`. « tour 34 » désigne donc le même tour des deux côtés.
- **Cache de prompt** : les ancres sont des **faits historiques immuables** ; `SUMMARY_MARKER` est
  une constante. Le résumé reste un préfixe stable — rien de volatil (règle mémoire
  « préfixe stable = rien de volatil »).
- **Gateway-First / Provider-First** : la synthèse passe par `AiAgentProvider` (inchangé) ; aucun
  moteur IA, aucune dépendance directe Anthropic.
- **Piège des constructeurs** : aucune modification de `AtelierCompactionProperties`.
