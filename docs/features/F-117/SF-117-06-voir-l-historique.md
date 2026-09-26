# Mini-spec — F-117 / SF-117-06 : l'historique d'avant un nouveau départ se replie derrière « Voir l'historique »

## Identifiant

`F-117 / SF-117-06`

## Feature parente

`F-117` — Le contexte d'un terminal ne déborde jamais

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-117-06-voir-l-historique`

---

## Objectif

Après un nouveau départ manuel, replier les tours d'avant la frontière derrière un affordance
« Voir l'historique » masqué par défaut, ré-affichable et repliable, sans casser le rail ni
l'auto-scroll — et rendre la copie honnête.

---

## Comportement attendu

### Cas nominal

1. Le parent (`AtelierComponent`) passe au terminal `[foldedTurns]` issu de l'état de reprise
   (`resume.foldedTurns`, mis à jour aussi après `restart`).
2. `foldedTurns > 0` : le terminal **masque** les `foldedTurns` premiers messages et affiche, en
   tête du fil, un affordance compact « **Voir l'historique (N messages)** ».
3. Un tap révèle les messages masqués et l'affordance devient « **Masquer l'historique** » ; un
   nouveau tap les replie. État local d'écran (signal), défaut = replié.
4. `foldedTurns == 0` (aucun nouveau départ, ou compaction auto seule) : **aucun** affordance, fil
   complet affiché — comportement inchangé.
5. Tient au **rechargement** : `foldedTurns` vient du serveur (SF-117-05), le repli se réapplique.
6. Copie rendue honnête : bouton d'en-tête (tooltip), bandeau de suggestion (SF-117-03) et snackbar
   de `restartThread()` ne disent plus « la conversation reste affichée » mais que l'historique est
   **replié** et accessible via « Voir l'historique ».

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `foldedTurns` > nombre de messages chargés | Bornage : on ne masque jamais plus que ce qui existe ; pas de crash |
| `foldedTurns` absent / backend ancien | Traité comme 0 ⇒ aucun repli (repli gracieux) |
| Lecture seule (tuile mosaïque F-83) | Affordance de divulgation tolérée (lecture, pas un geste émis au parent) ; défaut replié |

---

## Critères d'acceptation

- [ ] `foldedTurns > 0` ⇒ les N premiers messages sont masqués et un affordance « Voir l'historique »
      avec le nombre masqué apparaît en tête du fil.
- [ ] Tap sur l'affordance révèle l'historique ; re-tap le replie.
- [ ] `foldedTurns == 0` ⇒ aucun affordance, fil complet (non-régression).
- [ ] Le repli tient au rechargement (piloté par la donnée serveur).
- [ ] La compaction automatique ne provoque **aucun** repli (elle laisse `foldedTurns == 0`).
- [ ] Desktop **et** mobile (< 819 px) : affordance lisible, cible tactile ≥ 44 px.
- [ ] Rail « Vos questions » (SF-158-22) cohérent avec ce qui est affiché ; aucun saut cassé.
- [ ] L'auto-scroll au fond (SF-158-23) n'est pas déclenché par la révélation de l'historique
      (révéler ne fait pas sauter le fil en bas).
- [ ] Copie honnête : plus aucune mention « la conversation reste affichée » sur le chemin du
      nouveau départ (bouton, bandeau, snackbar).
- [ ] `--cg-*` uniquement, aucune couleur/police nouvelle ; `ng build` vert (budgets respectés).

---

## Périmètre

### Hors scope (explicite)

- Toute persistance / marqueur serveur → **SF-117-05** (fait).
- Modifier le comportement de la compaction automatique (elle reste silencieuse, tout affiché).
- Numérotation globale des questions (Q1, Q2…) : conservée (basée sur le fil complet).

---

## Technique

### Endpoint(s)

Aucun nouvel appel : réutilise `getResume` / `restartThread` (déjà appelés), enrichis en SF-117-05.

### Composants Angular

- `AtelierTerminalComponent` :
  - `@Input() foldedTurns` (défaut 0) ; signal `historyRevealed` (défaut `false`) ;
    getter `displayedMessages` (masque les N premiers si non révélé) ; `foldedCount` borné ;
    `toggleHistory()` ; suppression d'un cycle d'auto-scroll au toggle.
  - `userQuestions` aligné sur `displayedMessages` (rail cohérent), numérotation globale conservée.
  - Affordance dans le gabarit, styles dans la feuille `atelier-terminal-compaction.component.scss`
    (thème « gestion de contexte » F-117, sous budget) ou feuille dédiée si le budget l'exige.
  - Copie honnête : tooltip du bouton d'en-tête + note du bandeau de suggestion.
- `AtelierComponent` : câble `[foldedTurns]="resumeFoldedTurns()"` ; met à jour le signal depuis
  `loadResumeState` et `restartThread` ; snackbar de `restartThread()` rendue honnête.
- Modèle `AtelierResume` : ajout du champ `foldedTurns`.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable (frontend)

---

## Plan de test

### Tests unitaires (`atelier-terminal.component.spec`)

- [ ] `foldedTurns > 0` ⇒ masque les N premiers, affiche l'affordance avec le nombre.
- [ ] Toggle : révèle puis replie.
- [ ] `foldedTurns == 0` ⇒ aucun affordance, tous les messages rendus.
- [ ] `foldedTurns` > nombre de messages ⇒ borné, pas de crash.
- [ ] Rail cohérent avec `displayedMessages`.

### Tests unitaires (`atelier.component.spec`)

- [ ] `loadResumeState`/`restartThread` alimentent `foldedTurns` transmis au terminal.
- [ ] Snackbar du nouveau départ : nouvelle copie (plus « reste affichée »).

### Isolation workspace

- [x] Non applicable — composant de présentation, aucun accès données (isolation portée par le
      backend SF-117-05).

---

## Dépendances

### Subfeatures bloquantes

- `SF-117-05` (backend, `foldedTurns`) — doit être mergée avant.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Auto-scroll (SF-158-23)** : `ngAfterViewChecked` défile au fond quand `scrollHeight` change.
  Révéler l'historique augmente `scrollHeight` → il faut **absorber** ce changement (mettre à jour
  `lastScrollHeight` sans défiler) pour ce cycle, sinon révéler ferait sauter le fil en bas —
  l'inverse de ce que l'utilisateur veut.
- **Numérotation des questions** : reste globale (basée sur le fil complet) ; le rail liste les
  questions **affichées** avec leur numéro global — honnête après repli (Q5, Q6…).
- **Lecture seule** : l'affordance est une divulgation locale (comme `toggleBlock`/`toggleDiff`,
  déjà présents en lecture seule), pas un geste émis au parent — donc tolérée.
