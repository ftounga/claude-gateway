# Mini-spec — F-117 / SF-117-10 : retrait du bandeau « conversation longue » (40 tours)

## Identifiant

`F-117 / SF-117-10`

## Feature parente

`F-117` — Le contexte d'un terminal ne déborde jamais (cadrage `CADRAGE-F-117-voir-la-passation.md`)

## Statut

`ready`

## Date de création

2026-10-11

## Branche Git

`feat/SF-117-10-retrait-bandeau-40-tours`

---

## Objectif

Supprimer le bandeau « Cette conversation est longue — l'alléger ? » (seuil fixe de 40 tours,
SF-117-03) : la compaction automatique fait ce travail au bon moment ; garder « Nouveau départ » et
« Compacter maintenant » accessibles avec un libellé clair.

---

## Comportement attendu

### Cas nominal

1. Plus aucun bandeau de suggestion, quel que soit le nombre de tours (`LONG_THREAD_TURNS`,
   `suggestRestart`, `dismissRestartSuggestion`, `restartSuggestionDismissed` retirés).
2. L'entrée `@Input() threadTurns` du terminal n'a plus d'usage : elle est retirée, ainsi que son
   passage par le parent — la donnée `resume.turns` reste servie par l'API.
3. Bouton d'en-tête **« Nouveau départ »** conservé ; infobulle/aria : *repartir à blanc pour changer
   de sujet* (l'historique reste derrière « Voir l'historique »).
4. **« Compacter maintenant »** reste dans l'en-tête et le menu (déjà présent, F-162 / SF-162-04).
5. Docs : SF-117-03 marquée « bandeau retiré par SF-117-10 ».

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Fil de 200 tours | Aucun bandeau ; la compaction automatique opère au seuil de tokens |
| Lecture seule | Inchangé (pas de bandeau, pas de gestes) |

---

## Critères d'acceptation

- [ ] Aucun `.terminal-compaction-hint` rendu, même avec un fil très long.
- [ ] « Nouveau départ » présent, infobulle « repartir à blanc pour changer de sujet ».
- [ ] « Compacter maintenant » toujours accessible (en-tête + menu).
- [ ] Aucun code mort (`LONG_THREAD_TURNS`, `suggestRestart`, styles du bandeau) ; `ng build` + tests verts.

---

## Plan de test

- Spec terminal : les tests du bandeau (SF-117-03) sont remplacés par « aucun bandeau même à 200
  tours » ; l'infobulle du Nouveau départ est vérifiée ; « Compacter maintenant » présent.
- Pas de backend : aucune donnée, aucun accès (isolation inchangée).

---

## Impacts

- **Front** : `atelier-terminal.component.{ts,html,scss,spec.ts}`, parent qui passait `[threadTurns]`.
- **Back / tables / endpoints** : aucun.
- Préoccupations transversales : aucune.

## Hors périmètre

- Modifier le seuil ou le comportement de la compaction automatique.
- Retirer le « Nouveau départ » lui-même.
