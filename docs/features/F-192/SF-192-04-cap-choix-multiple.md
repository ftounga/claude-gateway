# SF-192-04 — Cap : cocher plusieurs suites

**Feature parente :** F-192 Cap « Et maintenant ? » · **Type :** frontend seul · **Estimation :** 0,5 j
**Demande PO (2026-10-12) :** « pouvoir en faire plusieurs en même temps, sous forme de checkbox ».

## Objectif
Pouvoir cocher plusieurs suites proposées à la fois : la saisie reçoit une seule demande qui les enchaîne.

## Comportement nominal
- Chaque option porte une case à cocher (`role="checkbox"`, `aria-checked`). Un clic ou la touche 1-4 **coche / décoche** l'option.
- La saisie est recalculée à chaque coche, dans l'ordre d'affichage des options :
  - 0 cochée ⇒ saisie vidée (seulement si elle contient encore le texte posé par le panneau) ;
  - 1 cochée ⇒ la demande de l'option, telle quelle (comportement d'avant) ;
  - 2 et plus ⇒ `Enchaîne ces suites, dans l'ordre : 1) <demande> ; 2) <demande> …` (une ligne, le champ est mono-ligne).
- Le panneau **reste ouvert** tant que la saisie vaut le texte qu'il a posé : on peut cocher plusieurs options à la suite.
- En-tête : « N cochée(s) » dès qu'une option est cochée. Pied : « 1–N cocher ».
- Rien n'est envoyé sans geste (inchangé).

## Cas limites / erreurs
- L'utilisateur modifie ou tape dans la saisie ⇒ le texte lui appartient : le panneau se replie (comportement d'avant), les coches ne réécrivent plus la saisie.
- Nouveau tour ⇒ coches remises à zéro.
- « Autre chose », Masquer, Échap : inchangés.

## Critères d'acceptation
1. Cocher 1 puis 3 ⇒ la saisie contient la phrase d'enchaînement avec les demandes 1 et 3 dans cet ordre ; les deux options sont `aria-checked="true"`.
2. Décocher la dernière option ⇒ la saisie est vidée.
3. Après une coche, le panneau reste déplié ; si la saisie diffère du texte posé, il se replie.
4. Une seule option cochée ⇒ saisie = sa demande exacte (non-régression SF-192-02).
5. Les touches 1-4 cochent/décochent, toujours ignorées dans un champ.

## Plan de test
- Unitaires (`terminal-next-steps.component.spec.ts`) : coche multiple, composition, décoche ⇒ vide, reste ouvert sur son propre texte, repli sur texte utilisateur, touches, nouveau tour ⇒ remise à zéro.
- Intégration (`terminal-et-maintenant.spec.ts`) : deux clics ⇒ `draftChange` reçoit la phrase composée.
- Isolation utilisateur : sans objet (aucune donnée, aucune requête).

## Impacts
- Composants : `terminal-next-steps.component.ts`, `atelier-terminal.component.ts` (`onNextStepPick` accepte une saisie vide sans voler le focus).
- Aucun endpoint, aucune table. Préoccupations transversales : aucune.

## Hors périmètre
- Envoi en plusieurs tours successifs (une seule demande composée).
- Changer ce que l'agent propose (backend SF-192-01 inchangé).
