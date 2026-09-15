# Mini-spec — [F-119 / SF-119-02] Discipline d'investigation dans le prompt

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage : `docs/features/F-119/CADRAGE-F-119-justesse-de-l-agent.md` (Cause 2).

---

## Identifiant

`F-119 / SF-119-02`

## Feature parente

`F-119` — La justesse de l'agent : se tromper moins, se corriger moins

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-119-02-discipline-prompt`

---

## Objectif

> En une phrase : donner au prompt système de la boucle maison des **consignes de discipline
> d'investigation non négociables** (vérifier avant d'affirmer, prouver avant de conclure, ne jamais
> généraliser d'un seul exemple, relire la source, corriger tôt, dire « non concluant » plutôt
> qu'inventer) et **compléter les contrats d'outils** `edit_file`/`write_file`, pour tarir à la source
> les 39 % d'erreurs « affirmé/généralisé sans vérifier ».

---

## Comportement attendu

### Cas nominal

1. `buildSystemPrompt` construit la consigne. Après l'énoncé du **rôle** — sur les **deux** cibles,
   `RUNNER` et `SANDBOX` — un paragraphe de **discipline d'investigation** est ajouté (texte sobre,
   français, cohérent avec le ton existant).
2. La description de l'outil `edit_file` est complétée : copier `old_string` **exactement**
   (indentation et espaces compris), **lire le fichier avant d'éditer**, **relire avant de réessayer**
   en cas d'échec.
3. La description de `write_file` précise qu'elle **écrase tout le fichier** et qu'il faut **préférer
   `edit_file`** pour une modification ciblée.
4. Le résultat reste borné par `SYSTEM_MAX_CHARS` (la discipline, placée en tête, survit à la coupe).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| CLAUDE.md volumineux + beaucoup de skills → dépassement `SYSTEM_MAX_CHARS` | La consigne est tronquée en fin (comportement existant) ; la discipline, en tête, est préservée |
| Cible `SANDBOX` (pas de machine / pas de `bash`) | La discipline est présente aussi ; elle ne mentionne pas d'outil absent (formulée indépendamment de l'interpréteur) |
| Terminal Teams / Radar / Pages (paragraphes conditionnels existants) | La discipline s'ajoute sans déplacer ni casser ces blocs |

---

## Critères d'acceptation

- [ ] Le prompt d'un projet **RUNNER** contient les consignes de discipline (assertions sur des
      substrings clés : vérifier avant d'affirmer, ne pas généraliser d'un seul exemple, relire la
      source, corriger tôt, « non concluant »).
- [ ] Le prompt d'un projet **SANDBOX** contient les **mêmes** consignes de discipline.
- [ ] La description `edit_file` mentionne « exactement » (indentation/espaces), « lis le fichier
      avant » et « relis avant de réessayer ».
- [ ] La description `write_file` mentionne qu'elle écrase tout le fichier et de préférer `edit_file`.
- [ ] Aucun test existant verrouillant un substring du prompt ou une panoplie d'outils ne casse.
- [ ] Le prompt reste ≤ `SYSTEM_MAX_CHARS`.

---

## Périmètre

### Hors scope (explicite)

- La ré-escalade d'effort (SF-119-01, livrée), la mémoire des preuves (SF-119-03), la sortie partielle
  des bash (SF-119-04), le suivi d'état de fichier (SF-119-05).
- Toute modification du **comportement** des outils (edit_file/chemins/shell restent sains — on
  complète la **description**, pas la logique).
- Une consigne configurable par l'utilisateur.

---

## Technique

### Composants impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `AtelierChatService.buildSystemPrompt` | Ajout d'un paragraphe de discipline après le rôle (RUNNER + SANDBOX) | Constante de texte partagée |
| `AtelierChatService.fileTools` | Descriptions `edit_file` / `write_file` enrichies | Aucune évolution de schéma d'outil |

### Endpoint(s) / Tables / Migration

Aucun. Prompt système uniquement.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceSystemPromptTest` — la discipline apparaît sur cible RUNNER (substrings) ;
      apparaît sur cible SANDBOX ; les substrings de rôle et d'outillage existants restent présents
      (non-régression) ; descriptions `edit_file`/`write_file` à jour (via la panoplie déclarée).

### Tests d'intégration

- [ ] Non applicable (pas d'endpoint) ; le contexte Spring existant couvre le démarrage.

### Isolation utilisateur

- [ ] Non applicable — aucune donnée nouvelle ; la lecture de CLAUDE.md/skills passe déjà par la
      cible d'exécution isolée `user_id`/`workspace_id` (inchangé).

---

## Préoccupations transversales

- **Auth / tenant / plans / navigation** : non — texte de prompt, aucun endpoint, aucun accès
  données nouveau, aucune consommation de jeton nouvelle (le prompt est déjà envoyé à chaque tour ;
  quelques centaines de caractères de plus, cachés dans le préfixe stable → cache de prompt préservé).

---

## Notes et décisions

- **Décision par défaut** : discipline **toujours active** (pas de drapeau) — c'est une consigne, pas
  un mécanisme risqué ; réversible par simple retrait de texte si besoin. Cohérent avec le fait que
  les autres consignes du rôle ne sont pas non plus derrière un drapeau.
- Texte inspiré de l'esprit Claude Code, mais formulé maison et sobre, pour ne pas gonfler le préfixe.
