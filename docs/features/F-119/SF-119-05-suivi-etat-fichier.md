# Mini-spec — [F-119 / SF-119-05] Suivi d'état de fichier

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage : `docs/features/F-119/CADRAGE-F-119-justesse-de-l-agent.md` (Cause 5).

---

## Identifiant

`F-119 / SF-119-05`

## Feature parente

`F-119` — La justesse de l'agent : se tromper moins, se corriger moins

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-119-05-suivi-etat-fichier`

---

## Objectif

> En une phrase : inciter le modèle à la **lecture-avant-édition** par un aide-mémoire léger — quand
> il édite (`edit_file`) un fichier qu'il n'a ni lu ni écrit dans le fil courant (édition « à
> l'aveugle »), le résultat porte un rappel de relire avant — **sans jamais refuser** l'opération.

---

## Comportement attendu

### Cas nominal

1. La boucle suit, par message, l'ensemble des chemins **lus ou écrits** par le modèle (`knownFiles`).
2. Un `read_file`, `write_file` ou `edit_file` réussi rend le chemin **connu** pour la suite du fil.
3. Un `edit_file` réussi sur un chemin **absent** de `knownFiles` (jamais lu ni écrit dans ce fil)
   voit un rappel léger ajouté à son résultat : « Rappel : tu as modifié `<path>` sans l'avoir lu dans
   ce fil. Relis-le avant de l'éditer si tu n'es pas sûr de son contenu. »
4. Réglable par `app.atelier.file-state-hints` (défaut `true`, coupe-circuit à `false`).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `read_file` avant `edit_file` du même fichier | **Aucun** rappel (comportement encouragé) |
| `edit_file` en échec (passage ambigu, texte introuvable) | Pas de rappel (le résultat est déjà une erreur) |
| `write_file` (écrase tout) sur un fichier non lu | Pas de rappel (la description SF-119-02 couvre déjà l'avertissement ; l'écriture n'a pas besoin d'une lecture) |
| Coupe-circuit `file-state-hints=false` | Aucun rappel, même sur une édition à l'aveugle |

---

## Critères d'acceptation

- [ ] Un `edit_file` d'un fichier non lu/écrit dans le fil porte le rappel de lecture-avant-édition.
- [ ] Un `read_file` puis `edit_file` du même fichier ne porte **aucun** rappel (non-régression).
- [ ] Le message d'édition (`Fichier modifié : … (N remplacement…)`) reste en tête, inchangé.
- [ ] Aucun **refus dur** nouveau : l'édition aboutit toujours (le disque évite la corruption).
- [ ] Le coupe-circuit `file-state-hints=false` supprime le rappel.

---

## Périmètre

### Hors scope (explicite)

- Détecter une modification « hors vue » par une commande `bash` (impossible de savoir de façon fiable
  quel fichier une commande a touché) — on couvre l'édition à l'aveugle, cas concret et détectable.
- Un refus dur de type « lis d'abord » (rejeté par le cadrage — priorité moindre, aide mémoire).
- Les autres SF de F-119 (livrées).

---

## Valeurs initiales / réglages

| Propriété | Clé env | Défaut | Règle |
|-----------|---------|--------|-------|
| `fileStateHints` | `APP_ATELIER_FILE_STATE_HINTS` | `true` | Absent ⇒ actif. `false` ⇒ aucun rappel |

---

## Technique

### Composants impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `AtelierChatService` | `knownFiles` par message ; `withFileStateHint(call, content, knownFiles)` | Rappel appended au `modelContent` |
| `AtelierProperties` | `fileStateHints` (fin du record) + défaut + compat ctor | |
| `application.yml` | clé `file-state-hints` | |

### Endpoint(s) / Tables / Migration

Aucun. Message rendu au modèle uniquement.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceTest` — édition à l'aveugle → rappel ; lecture-puis-édition → aucun rappel ;
      coupe-circuit → aucun rappel.
- [ ] `AtelierPropertiesTest` — défaut `true`, coupe-circuit `false`, constructeur de compatibilité.

### Tests d'intégration / isolation

- [ ] Non applicable — aucun accès données nouveau ; suivi en mémoire du tour, isolé par nature.

---

## Préoccupations transversales

- **Auth / tenant / plans / navigation** : non. Aucune consommation nouvelle (un rappel de quelques
  dizaines de caractères sur les seules éditions à l'aveugle), aucun endpoint, aucune route.

---

## Notes et décisions

- **Décision par défaut** : rappel actif, jamais bloquant. `write_file` non concerné (écrase tout,
  n'a pas besoin d'une lecture préalable ; SF-119-02 le dit déjà dans sa description).
- Suivi **par message** (à travers les tours de continuation) : « lu récemment » a du sens à l'échelle
  du fil courant, pas au-delà (un rechargement repart de zéro, ce qui est correct — l'aide-mémoire
  n'est pas un état persistant).
