# Mini-spec — [F-208 / SF-208-04] Les boutons du tableau des chantiers

## Identifiant
`F-208 / SF-208-04` — Feature parente `F-208` (Le tableau des chantiers) — Statut `in-review` — Créée 2026-10-24
Branche : `feat/SF-208-04-boutons-du-tableau` — cadrage : décision 8 et « Challenge PO ». Dépend de SF-208-03 (mergée).

## Objectif
Depuis l'overlay des chantiers, l'utilisateur pilote : faire une étape (confirmée si elle est externe), voir sa preuve, relancer l'attente dont elle dépend, mettre un chantier au focus — chaque action d'exécution partant comme une demande visible dans le fil.

## Comportement nominal
- **« Faire cette étape »** sur une étape à faire ou en échec :
  - actif seulement si le chantier est **en Exécution sur son plan validé** et l'étape **débloquée** ; sinon désactivé, avec la raison (« plan à valider d'abord », « bloquée : après l'étape 2 »).
  - étape **EXTERNE** : un premier clic ouvre une **confirmation explicite** dans la ligne (« Cette étape agit hors du poste (push, envoi, production…). Confirmer ? » [Confirmer] [Annuler]) ; rien ne part sans [Confirmer].
  - envoie la demande pré-remplie **« Chantier N — fais l'étape M : titre »**, visible dans le fil, par le chemin d'envoi normal (un brouillon en cours part avec elle, rien n'est perdu). Si un tour tourne, l'envoi devient une **précision** (interjection F-195) ; dans un terminal sans précision (bac à sable hébergé), il est refusé avec un message.
  - sur un chantier qui n'est pas le focus, la demande nomme son numéro : l'agent vise ce chantier (paramètre `chantier`, F-206).
- **« Voir la preuve »** (étape ou sous-étape porteuse d'une preuve) : déplie l'étape et amène la preuve en vue (surlignée).
- **« Relancer l'attente »** (étape dont l'attente est ouverte) : envoie « Chantier N — relance l'attente « clé » dont dépend l'étape M : titre ».
- **« Mettre au focus »** (onglet d'un chantier qui n'est pas le focus) : route existante `POST /chantiers/{n}/focus` ; l'onglet devient le focus, l'overlay reste ouvert.
- Les gestes d'envoi ferment l'overlay pour montrer la demande partie dans le fil. Lecture seule ou plafond atteint : boutons d'envoi absents.
- Aucun bouton « exécuter tout le plan », aucun parallélisme forcé (challenge PO) : l'agent garde le rythme, la porte F-176 et la sérialisation des actions non-lecture restent les garde-fous.

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Étape bloquée / plan non validé | bouton désactivé, raison affichée |
| EXTERNE sans confirmation | rien n'est envoyé |
| Tour en cours dans un terminal sans précision | message « Un tour est en cours : attendez sa fin pour lancer cette étape. » |
| Terminal en lecture seule ou plafond atteint | pas de bouton d'envoi |
| Échec de « Mettre au focus » | message « Le parcours du sujet n'a pas pu être mis à jour. » (existant), rien ne change |

## Critères d'acceptation
- [ ] CA1 « Faire cette étape » envoie « Chantier N — fais l'étape M : titre » par le chemin d'envoi (précision pendant un tour).
- [ ] CA2 Désactivé si l'étape est bloquée ou le plan pas en exécution validée, avec la raison.
- [ ] CA3 Étape EXTERNE : confirmation explicite obligatoire ; [Annuler] n'envoie rien.
- [ ] CA4 « Relancer l'attente » envoie la demande de relance nommant la clé.
- [ ] CA5 « Voir la preuve » déplie l'étape et surligne la preuve.
- [ ] CA6 « Mettre au focus » appelle la route focus du chantier visé.
- [ ] CA7 Charte respectée ; suite frontend et build verts.

## Plan de test
- `terminal-chantiers-board.component.spec.ts` : émissions `request` / `focus`, désactivation et raison, confirmation EXTERNE (confirmer / annuler), « Voir la preuve ».
- `terminal-tableau-chantiers.spec.ts` : la demande de l'overlay part par `draftChange` + `send` (brouillon conservé), précision pendant un tour, refus sans précision, focus via le service.
- Isolation utilisateur : sans objet (routes existantes, isolées côté serveur).

## Préoccupations transversales
Aucune (pas d'auth, de tenant, de plan tarifaire ni de route).

## Impacts
`terminal-chantiers-board.component.ts` (+ spec), `atelier-terminal.component.{ts,html}`, `terminal-tableau-chantiers.spec.ts`.

## Hors périmètre
Exécution automatique d'étapes, édition du plan, envoi groupé de plusieurs étapes (F-209 pour les prérequis).
