# Mini-spec — F-192 / SF-192-02 Le panneau « Et maintenant ? » (frontend)

## Identifiant
`F-192 / SF-192-02` — Feature parente : F-192 « Cap : Et maintenant ? » (cadrage `CADRAGE-F-192-cap-et-maintenant.md`, maquette `design/maquette-cap-plume.html` partie Cap).
Statut : `ready` — 2026-10-11 — Branche `feat/SF-192-02-panneau-et-maintenant`.

## Objectif
Montrer sous la dernière réponse de l'agent les suites qu'il a proposées, dans un panneau où un clic ou une touche remplit la saisie sans rien envoyer.

## Comportement attendu

### Contrat importé de SF-192-01 (mergé, PR #1157)
`done.nextSteps` (événement SSE) et `message.terminal.nextSteps` (historique) :
`[{ "title", "why", "label": "VERIFIER|CONTINUER|LIVRER|RISQUE", "request" }]` — absent ou vide ⇒ aucune suite.

### Cas nominal
1. Les suites sont recopiées du `done` (fin de tour) et du relevé (rechargement) sur l'élément du fil (`AtelierThreadItem.nextSteps`).
2. Panneau `app-terminal-next-steps` dans le flux, sous la dernière réponse : en-tête « Et maintenant ? » + « proposé par l'agent, d'après ce tour » + bouton « Masquer » ; options numérotées 1..N (titre, « pourquoi », étiquette colorée) ; ligne « Autre chose : tapez votre suite » (donne le focus à la saisie) ; pied « 1-N choisir · Échap masquer · Rien n'est envoyé sans vous ».
3. **Un clic ou une touche 1-4 remplit la saisie** (et lui donne le focus), **n'envoie rien** ; l'option choisie est marquée.
4. **Repli** : dès que la saisie n'est pas vide, ou après « Masquer » / Échap, le panneau devient une ligne « N suites proposées » + « Afficher ».
5. Étiquettes : Vérifier = vert (`--cg-success`), Continuer = or (`--cg-accent`), Livrer = orange (`--cg-orange-2`), Risque = ambre (`#F9A825`, déjà employé par la bande F-191).
6. Pendant le flux, le bloc brut `<<suites>>…` n'est jamais affiché (retiré du texte vivant avant rendu, ouverture seule comprise).
7. Mobile (≤ 600 px) : l'étiquette passe sous le titre.

### Ne s'affiche pas
Lecture seule ; tour en cours ; question F-164 en attente ; dernier élément du fil qui n'est pas une réponse de l'agent ; tour interrompu ou au plafond ; aucune suite.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Backend antérieur (pas de `nextSteps`) | aucun panneau, aucune erreur |
| `nextSteps` non tableau / étiquette inconnue | ignoré / étiquette rendue « Continuer » |
| Touche 1-4 tapée dans un champ de saisie | ignorée par le panneau (elle s'écrit dans le champ) |
| Touche d'un numéro au-delà du nombre d'options | ignorée |

## Contraintes de validation
- 4 options au plus affichées ; touches 1..4 ; aucune requête réseau émise par le panneau.

## Critères d'acceptation
- [ ] Un `done` portant 3 suites fait apparaître 3 options sous la réponse ; au rechargement elles réapparaissent.
- [ ] Clic / touche « 2 » : la saisie reçoit la demande de l'option 2 ; aucun envoi.
- [ ] Saisie non vide ⇒ ligne repliée « 3 suites proposées » ; « Masquer » et Échap replient aussi ; « Afficher » rouvre.
- [ ] Absent en lecture seule, pendant un tour, avec une question en attente, après un tour interrompu ou au plafond.
- [ ] Le texte vivant ne montre jamais `<<suites>>`.
- [ ] Couleurs et polices du design system uniquement.

## Plan de test
- `terminal-next-steps.component.spec.ts` : rendu des options et étiquettes, clic ⇒ `pick`, touches 1-4 hors champ, touche dans un input ignorée, Échap / Masquer ⇒ repli, saisie non vide ⇒ repli, « Afficher ».
- `essential.spec.ts` (ajout) : `stripNextSteps` (bloc fermé, ouverture seule, absent) et `splitEssential` ne montre pas le bloc.
- `atelier-terminal` : `nextStepsOfTurn()` vide en lecture seule / pendant un tour / tour interrompu / question en attente ; rempli sinon ; `onNextStepPick` émet `draftChange` sans `send`.
- Mapping : `toThreadItem` recopie `terminal.nextSteps` ; le relais SSE `done` recopie `nextSteps`.
- Isolation utilisateur : aucune nouvelle route ; les suites voyagent avec des messages déjà filtrés par `user_id`.

## Impacts
- Tables / endpoints : aucun.
- Composants : `atelier.models.ts`, `atelier.types.ts`, `atelier.service.ts` (relais `done`), `atelier.component.ts` (`appendTurnReply`, `toThreadItem`), `atelier-terminal.component.*`, `essential.ts`, nouveau `terminal-next-steps.component.ts`.
- Préoccupations transversales : Auth ☐ ; Tenant ☐ ; Plans/limites ☐ ; Navigation ☐.

## Hors périmètre
Retrait de la suite prédite F-144 et des puces (SF-192-03 — ils cohabitent le temps de cette PR) ; envoi automatique ; suites ailleurs que dans le terminal (mosaïque en lecture seule).
