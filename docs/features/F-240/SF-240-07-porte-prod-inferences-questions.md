# Mini-spec — F-240 / SF-240-07 — Porte prod : inférences et questions en retour (consigne v12)

## Identifiant
`F-240 / SF-240-07` · Feature parente `F-240` « Jarvis va à l'essentiel » · Statut `in-progress` · 2026-11-03 ·
Branche `feat/SF-240-07-porte-prod-inferences-questions`

## Objectif
> Rouvrir la porte du banc CONVERSATION en prod en corrigeant le **comportement** : jamais de lien de cause ou de
> dépendance que le contexte n'énonce pas, et au plus une question de politesse par conversation — jamais les seuils
> ni le juge.

## Constat (banc CONVERSATION en prod sur `9e9866cd`, consigne v11, le 03/11)
96,4 % (53/55), porte FERMÉE « 1 fait(s) non ancré(s) » ; PIECES et parole verts.

| Scénario | Ce qui a été dit | Qui l'a classé | Cause | Nature |
|---|---|---|---|---|
| P08#4 « On passe à la suite. » | « Passons au compte rendu de recette pour Claire Dumas, Monsieur : il reste à envoyer, et sa validation du portail d'Orion-Run en dépend. » | le **juge** (`fait_non_ancre`) ; le détecteur déterministe `ungroundedFact` ne voit que nombres, dates, tickets, noms propres — tous ancrés ici | le contexte dit « Claire Dumas doit valider la recette du portail » et « A envoyer : compte rendu de recette a Claire Dumas » ; **aucune ligne ne lie les deux** : « en dépend » est une inférence inventée — **vrai défaut** | modèle (consigne) + absence de filet |
| P16#2 « Calme, la matinée. » | « Voilà qui laisse le champ libre, Monsieur. Du nouveau depuis notre dernier entretien ? » | la règle du banc « plus d'une question en retour » (tour 1 : « La semaine commence bien ? ») | le contexte du tour 2 dit « Question de politesse déjà posée : oui » ; la note de relance du portrait l'a emporté — **vrai défaut** (règle F-239 : une question de politesse par conversation) | modèle ; aucune garde |

## Comportement attendu
### Cas nominal
- **Consigne du cerveau rapide v12** (une seule nouvelle version, texte figé, rien de volatil) :
  - « Ancrage » : aucun lien de cause, de condition ou de dépendance entre deux faits (« X en dépend », « parce
    que », « ce qui bloque ») que le contexte n'énonce pas ; deux lignes voisines ne sont pas liées pour autant.
  - « Converser » : « Question de politesse déjà posée : oui » vaut pour toute la conversation, même quand une note
    propose une relance.
- **Filet déterministe « lien inventé »** (porte des phrases) : une phrase qui porte un marqueur de cause ou de
  dépendance (`dépend…`, `conditionne…`, `parce que`, `puisque`, `car`, `à cause de`, `faute de`, `si bien que`,
  `c'est pourquoi`, `préalable`, `découle…`, `en conséquence`) **absent du contexte et de la parole** :
  - la partie liée est **retirée** (à partir de la conjonction, ou de la proposition qui porte le verbe de
    dépendance) et le reste est dit s'il garde au moins trois mots : « … : il reste à envoyer, et sa validation … en
    dépend. » ⇒ « … : il reste à envoyer. » ;
  - sinon la phrase est retirée si une autre a déjà été dite ; sinon (rien à dire) délégation d'office comme une
    phrase non ancrée (`grounding`) ; sur une salutation, retirée sans délégation.
  - Le marqueur présent dans le contexte (une réponse d'Opus qui dit « dépend ») ⇒ permis.
- **Garde « une question de politesse par conversation »** (sans appel au modèle) : le contexte dit « Question de
  politesse déjà posée : oui » ⇒ une phrase-question de politesse (« Du nouveau depuis notre dernier entretien ? »,
  « Bien dormi ? », « La journée commence bien ? », « Et vous ? »…) est **retirée** si une autre phrase a déjà été
  dite (la réponse ne devient jamais vide). Jamais une question de clarification ou de confirmation (« Lequel,
  Monsieur ? », « Vous confirmez ? », « Voulez-vous que je génère la preuve ? »).
- **Relance du portrait (prod)** : la note « Du nouveau ? » n'est pas ajoutée au message quand une question de
  politesse a déjà été posée (le moment ne s'y prête pas) ; dans le message, aucun effet sur le préfixe caché.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Marqueur dans la première proposition, rien d'autre à dire (« Ça dépend de Claire. ») | délégation d'office `grounding` (comme un fait non ancré) |
| Marqueur présent dans le contexte ou dans la parole (« ça dépend de quoi ? ») | permis |
| Question de politesse seule dans la réponse, déjà posée | dite (jamais une réponse vide) |
| « Question de politesse déjà posée : non » | la question reste dite |
| Une garde lève une exception | comportement existant : délégation d'office `grounding` |

## Critères d'acceptation
- [ ] `JarvisFastBrainPrompt.VERSION = "12"` ; consigne figée (préfixe identique d'un appel à l'autre) ; elle interdit
  les liens de cause/dépendance non énoncés et étend « déjà posée » à toute la conversation.
- [ ] La réponse P08#4 de prod rejouée (fournisseur simulé) ⇒ réponse dite « Passons au compte rendu de recette pour
  Claire Dumas, Monsieur : il reste à envoyer. », sans « dépend » (test).
- [ ] La réponse P16#2 de prod rejouée ⇒ « Voilà qui laisse le champ libre, Monsieur. » seule (test) ; la même
  question avec « déjà posée : non » reste dite ; seule, elle reste dite (tests).
- [ ] Faux positifs : marqueur présent dans le contexte, « Vous confirmez ? », « Lequel, Monsieur ? » (tests).
- [ ] Seuils et juges inchangés (tests existants).
- [ ] Pré-bancs live (clé lue à la volée) : `JarvisConversationBenchLiveTest`, `JarvisPiecesBenchLiveTest`,
  `JarvisOralBenchLiveTest` — deux passages verts consécutifs chacun (3 passages de mesure pour CONVERSATION) ;
  budget ≤ 15 €.

## Périmètre — hors scope
Seuils, juges, sévérité, scénarios du banc ; l'écran ; déployer ; escalade de modèle (jamais).

## Contraintes de validation
| Champ | Obligatoire | Valeurs | Normalisation |
|---|---|---|---|
| Marqueur de lien | — | liste fermée ci-dessus | minuscules, sans accents |
| Reste dit après retrait | — | ≥ 3 mots hors « Monsieur » | ponctuation finale « . » |

## Technique
- Aucun endpoint, aucune table, aucune migration.
- `JarvisFastBrainPrompt` (v12), `JarvisFastGuards` (`withoutInventedLink`, `isCourtesyQuestion`),
  `JarvisFastBrainService` (porte des phrases : lien inventé, question déjà posée ; note de relance).
- Isolation : aucun nouvel accès aux données ; la mémoire du jour reste lue sous `user_id` (inchangé).

## Préoccupations transversales
Aucune (ni auth, ni tenant, ni plans/limites, ni routage).

## Plan de test
- Unitaires : `JarvisFastGuardsTest` (lien inventé : retrait, marqueur connu, phrase non récupérable ; question de
  politesse : reconnue, clarification/confirmation non reconnues) ; `JarvisFastBrainServiceTest` (P08#4 et P16#2
  rejoués ; consigne v12).
- Intégration : suite backend complète (contexte Spring).
- Pré-bancs live CONVERSATION (3 passages), PIECES et parole (2 passages chacun).

## Dépendances
SF-240-06 (#1554) mergée. Aucune question ouverte.
