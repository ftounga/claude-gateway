# Mini-spec — [F-207 / SF-207-02] Rail « Vos questions » en lisière

## Identifiant
`F-207 / SF-207-02` — Feature parente `F-207` — Statut `ready` — Créée 2026-10-21
Branche : `feat/SF-207-02-questions-en-lisiere` — dépend de rien (indépendante de SF-207-01).

## Objectif
Le rail « Vos questions » rend au fil ses 232 px : il se replie en une lisière de numéros à droite et s'ouvre en surimpression au survol.

## Comportement nominal
- À partir de 820 px, le fil occupe toute la largeur moins une **lisière de 32 px** à droite.
- La lisière montre, collée en haut (sticky), les pastilles numérotées des questions (les 12 dernières au plus, puis « +N ») dans le style actuel des numéros, et une icône discrète `format_list_numbered`.
- **Ouvrir** : survol de la lisière ou focus d'une de ses pastilles → le rail complet (titre, numéros, libellés, 280 px) glisse depuis la droite **par-dessus** le fil, fond `--cg-navy` légèrement surélevé, ombre de la charte. Fermeture 350 ms après la sortie de la souris, ou par Échap.
- Un clic sur une pastille (lisière ou rail) saute à la question, comme aujourd'hui.
- Sous 820 px : rendu inchangé (rail en tête, F-158).
- Lecture seule (mosaïque) : rien, comme aujourd'hui.

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Aucune question | ni lisière ni rail, fil pleine largeur |
| Plus de 12 questions | 12 dernières pastilles + « +N » (le rail ouvert montre tout, défilable) |
| `prefers-reduced-motion` | ouverture sans glissement |

## Critères d'acceptation
- [ ] CA1 À 1366 px avec 4 questions : la colonne du fil fait (largeur − 32 px − gouttière) au lieu de (largeur − 232 px − gouttière).
- [ ] CA2 Survol de la lisière → rail complet visible en surimpression ; la largeur du fil ne change pas.
- [ ] CA3 Clic sur une pastille → `scrollToQuestion` avec la bonne ancre.
- [ ] CA4 Plus de 12 questions → 12 pastilles + « +N ».
- [ ] CA5 Non-régression : mobile (rail en tête, `terminal-rail-en-tete.spec.ts`), lecture seule sans rail, suite verte, build vert.

## Plan de test
- `terminal-questions-lisiere.spec.ts` (nouveau) : largeur réelle du fil ChromeHeadless (CA1), ouverture au survol et au focus, fermeture différée (CA2), saut (CA3), troncature (CA4).
- Specs existantes du rail mises à jour si elles mesurent la piste de 232 px.
- Isolation utilisateur : sans objet.

## Impacts
- Tables / endpoints : aucun. Composants : `AtelierTerminalComponent` (gabarit du rail, état d'ouverture), `atelier-terminal-questions.component.scss`.
- Préoccupations transversales : aucune.
- Design system : couleurs existantes uniquement.

## Hors périmètre
Contenu des questions, mobile, mosaïque.
