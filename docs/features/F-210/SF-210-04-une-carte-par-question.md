# Mini-spec — F-210 / SF-210-04 — Une carte de réponse par question (front)

## Identifiant
`F-210 / SF-210-04` — feature parente `F-210` *Deux questions, deux fils*. Branche prévue :
`feat/SF-210-04-carte-par-question`. Statut : `ready` (à livrer **après F-209**, dont elle réutilise le
composant de suites en deux colonnes). Date : 2026-10-24.

## Objectif
Quand la réponse d'un tour porte `answers[]` (SF-210-02), le terminal affiche une carte par question —
son essentiel, son détail, ses suites, son badge de chantier, « non aboutie » le cas échéant — et une seule
carte avec la mention « Fusionnées : raison » quand les questions ont été fusionnées.

## Comportement attendu

### Cas nominal
1. **Source** : fin de flux — `answers` du dernier événement `questions` (gardé par SF-210-03 sur
   `AtelierThreadItem.answers`) ; historique — `terminal_json.answers` (relu par SF-210-03).
2. **Carte par réponse** (`app-terminal-answer-card`, dans l'ordre des questions) :
   - en-tête : numéro, libellé, badge du chantier (« Ch.N · étape k/n » depuis `chantier`, sinon
     « Hors chantier ») ;
   - « L'essentiel » (si présent) puis « Le détail » (Markdown, même rendu que la réponse actuelle :
     `renderMarkdown`, DOMPurify) ;
   - les suites de la question avec le **composant de suites réutilisable de F-209** (deux colonnes Chantier /
     Hors chantier) ; un clic remplit la saisie comme aujourd'hui ;
   - statut `NON_ABOUTIE` : marque « Non aboutie » sur la carte, sans masquer les autres cartes.
3. **Fusion** (`questions.fusion` présent, pas d'`answers`) : la réponse habituelle, précédée de
   « Fusionnées : raison » et du bouton « Séparer » (SF-210-03).
4. **Rétro-compatibilité** : sans `answers`, rendu actuel inchangé (essentiel/détail du `content`, panneau
   « Et maintenant ? » global). Avec `answers`, le `content` (texte lisible de SF-210-02) n'est pas rendu
   en double : les cartes le remplacent ; le panneau global « Et maintenant ? » n'est pas affiché (les suites
   sont dans les cartes).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Réponse sans essentiel | la carte montre le détail seul |
| Réponse vide et non aboutie | « Cette question n'a pas abouti dans ce tour. » |
| `answers` illisible | repli sur le rendu actuel du `content` |

## Critères d'acceptation
- [ ] Deux `answers` ⇒ deux cartes, chacune avec son essentiel, son détail et ses suites.
- [ ] Une carte `NON_ABOUTIE` est marquée, l'autre reste intacte.
- [ ] Badge chantier ou « Hors chantier » sur chaque carte.
- [ ] Fusion ⇒ une carte avec « Fusionnées : raison ».
- [ ] Rechargement : mêmes cartes depuis `terminal_json.answers`.
- [ ] Message sans `answers` : rendu strictement inchangé.

## Plan de test
- **Unitaires** : normalisation des réponses, libellé du badge, choix du rendu (cartes / repli).
- **Composant** : `terminal-answer-card.component.spec.ts` ; `atelier-terminal.component.spec.ts` (cartes en
  fin de tour, historique, repli, fusion).
- `ng test` + `npm run build`.

## Impacts
- **Composants** : `terminal-answer-card.component.ts` (nouveau), `atelier-terminal.component.{ts,html}`
  (rendu historique l.~941-960 et fin de tour), réutilisation du composant de suites F-209.
- **Endpoints / tables** : aucun.

### Préoccupations transversales
Aucune.

## Hors périmètre
Des sous-agents par question ; un plafond de budget par question.
