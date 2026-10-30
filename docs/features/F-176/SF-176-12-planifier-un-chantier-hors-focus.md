# SF-176-12 — Planifier un chantier hors focus, au clic ou par écrit

**Feature :** F-176 · **Statut :** Done (2026-10-30) · **Estimation :** < 1 j

## Objectif
Pouvoir faire passer en Plan un chantier dont le diagnostic attend (« Prêt à planifier ») même quand il n'est pas au focus.

## Constat (prod, agenor, 29-30/10)
Chantier 6 (Loki) : diagnostic posé le 29/10 16:44, focus sur le chantier 7. Le bouton « Planifier et continuer » n'existait que dans le bandeau du chantier au focus ; le tableau des chantiers marquait le 6 « Prêt à planifier » sans bouton. Trois confirmations écrites du PO refusées : l'agent n'avait aucun outil pour confirmer.

## Comportement
1. **Tableau des chantiers** : [Planifier] sur la carte d'un chantier `diagnosisPending` → `POST …/chantiers/{n}/diagnosis/confirm` (existant), focus inchangé, puis demande visible « ✓ Chantier N : diagnostic confirmé — pose le plan de ce chantier. ».
2. **Tableau du client** : [Planifier] (toujours visible) → confirme, met le chantier au focus, ouvre le sujet avec la demande prête à envoyer. Le DTO porte `diagnosisPending`.
3. **Confirmation écrite** : outil `confirmer_diagnostic` (chantier explicite + citation `user_words` exigés) ; le guide et la note de tour le disent. Jamais d'initiative, ni en course autonome.

## Erreurs
Rien à confirmer → message lisible (« Aucun diagnostic n'attend de confirmation. »), tour non interrompu. Chantier ou citation manquants → refus de l'outil.

## Critères d'acceptation
- [x] Chantier hors focus : [Planifier] le passe en PLAN sans changer le focus.
- [x] Outil : sans numéro ou sans citation, refus ; avec, PLAN ; puis set_subject_plan accepté.
- [x] Tableau du client : bouton visible seulement si `diagnosisPending`.

## Plan de test
- Intégration `SubjectJourneyChantiersIntegrationTest.confirmDiagnosisTool` (refus, confirmation, focus inchangé, plan posé).
- Specs `terminal-chantiers-board` (2) et `client-board` (1).
- Isolation : endpoint existant (`requireOwned` en premier) ; l'outil prend `userId`/`workspace` du tour.

## Impact
`JourneyToolCatalog`, `JourneyToolExecutor`, `JourneyTurnNote`, `ClientBoard(Service)` ; `terminal-chantiers-board`, `atelier-terminal` (relecture), `client-board`. Cache : le guide et la liste d'outils changent une fois (littéraux stables). Préoccupations transversales : aucune.

## Hors périmètre
« Continuer l'investigation » hors focus ; auto-envoi depuis le tableau du client (la demande est pré-remplie).
