# Mini-spec — [F-210 / SF-210-05] Chaque question garde ses suites

## Identifiant
`F-210 / SF-210-05`

## Feature parente
`F-210` — Deux questions, deux fils

## Statut
`in-progress`

## Date de création
2026-11-03

## Branche Git
`feat/SF-210-05-suites-par-question`

---

## Objectif
Dans une réponse découpée par question (cartes F-210), un geste sur les suites d'UNE question ne masque plus celles des autres : chaque question garde ses suites jusqu'à ce que le PO y ait répondu ou qu'une nouvelle réponse de l'agent les rende caduques.

## Constat (retour PO, terminal de l'Atelier)
3 questions, chacune avec ses suites. Un clic sur une suite de la question 2 fait disparaître les suites des questions 1 et 3.

## Cause racine (deux causes, même symptôme)
1. **État partagé : la saisie.** Tous les panneaux de suites (`app-terminal-next-steps`) reçoivent la même saisie (`[draft]`). Le panneau se replie « dès qu'on écrit autre chose » (`collapsed = draft non vide et draft ≠ ce que CE panneau a écrit`). Cocher une suite de Q2 écrit dans la saisie ; pour les panneaux de Q1 et Q3, ce texte n'est pas le leur ⇒ ils se replient en « N suites proposées · Afficher ». Le texte posé par un panneau frère est pris pour une frappe de l'utilisateur.
2. **« Consommé » global au message.** `answerStepsOf` n'affichait les suites que si le message était le **dernier élément du fil** et qu'aucun tour ne tournait (`submitting`/`streaming`). Dès qu'une suite part (« Lancer » ou Envoyer), le message du PO devient le dernier élément et le tour démarre ⇒ les suites de **toutes** les cartes disparaissent, y compris après rechargement de la page tant que la réponse n'est pas arrivée.

## Comportement attendu

### Cas nominal
- Cocher une suite de Q2 remplit la saisie ; les panneaux de Q1 et Q3 restent dépliés. Cocher ensuite une suite de Q1 **ajoute** sa demande à la saisie (les deux partent ensemble), sans écraser celle de Q2.
- Envoyer (ou « Lancer ») une suite de Q2 : Q2 est **répondue** — ses suites s'effacent ; Q1 et Q3 gardent les leurs, **pendant le tour qui démarre** et **après rechargement de la page**.
- Une question est répondue quand un message du PO postérieur à la réponse contient la demande d'une de ses suites (dérivé du fil persisté : tient au rechargement, aucune donnée nouvelle).
- Caducité : une **nouvelle réponse de l'agent** (message ASSISTANT postérieur) rend caduques toutes les suites de la réponse précédente (règle inchangée).

### Cas d'erreur / limites
| Situation | Comportement attendu |
|-----------|---------------------|
| Le PO tape librement dans la saisie (texte qui n'est pas celui posé par les panneaux) | Tous les panneaux se replient (« repli dès qu'on écrit », F-192 inchangé) |
| Une question attend une réponse structurée (`demander`, F-164) | Suites masquées (la carte F-164 suffit, inchangé) |
| Réponse interrompue / plafond atteint / lecture seule | Aucune suite (inchangé) |
| Message libre du PO ne reprenant aucune suite | Aucune question marquée répondue ; suites conservées |

## Critères d'acceptation
- [ ] 3 questions, coche d'une suite de Q2 ⇒ les panneaux de Q1 et Q3 restent dépliés et montrent leurs suites.
- [ ] Coche Q2 puis Q1 ⇒ la saisie contient les deux demandes.
- [ ] Message du PO reprenant la suite de Q2 + tour en cours ⇒ Q1 et Q3 montrent leurs suites, Q2 non.
- [ ] Même fil rechargé (messages relus, nouveaux objets) ⇒ même résultat.
- [ ] Nouvelle réponse de l'agent ⇒ plus aucune suite sur l'ancienne réponse.
- [ ] Frappe libre ⇒ repli de tous les panneaux (non-régression F-192).
- [ ] Panneau global « Et maintenant ? » (réponse non découpée) inchangé.

## Périmètre — hors scope
- Aucun changement backend, aucune persistance de « question répondue » (dérivée du fil).
- Pas de détection d'une réponse tapée librement à une question précise.

## Technique
- Endpoints : aucun. Tables : aucune. Migration : non applicable.
- Composants Angular : `AtelierTerminalComponent` (`answerStepsOf`, nouveaux `cardDraft`, `onCardStepPick`, `isAnswerAnswered`), gabarit des cartes F-210.

## Préoccupations transversales
Aucune (ni auth, ni tenant, ni plans, ni routing).

## Plan de test
### Unitaires (Jasmine, `terminal-answer-cards-f210.spec.ts`)
- [ ] 3 questions ; clic sur la suite de Q2 ⇒ Q1 et Q3 non repliées (DOM).
- [ ] Coche Q2 puis Q1 ⇒ saisie émise contient les deux demandes.
- [ ] Fil `[réponse 3Q, message PO = suite Q2]` + `streaming` ⇒ Q1/Q3 ont leurs suites, Q2 non.
- [ ] Même fil recréé (rechargement) sans tour ⇒ idem.
- [ ] Réponse agent postérieure ⇒ aucune suite (test existant conservé).
- [ ] Frappe libre ⇒ tous repliés.
### Intégration
- Non applicable (aucun endpoint) ; suite front complète `ng test` verte.
### Isolation utilisateur
- Non applicable — rendu pur des messages déjà isolés par `user_id` côté API.

## Dépendances
SF-210-04 (done), SF-209-02 (done), SF-192-04 (done). Aucune question ouverte impactée.
