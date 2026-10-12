# SF-195-01 — Consignes « plusieurs sujets » et « interjection qui s'ajoute »

**Feature parente :** F-195 Plusieurs sujets à la fois · **Type :** backend (consigne) + frontend (Cap) · **Estimation :** 0,5 j

## Objectif
Dire au modèle, dans le préfixe stable, de traiter plusieurs sujets en parallèle quand ils sont indépendants et de traiter une interjection comme un ajout — pas comme un remplacement.

## Comportement nominal
- Nouvelle doctrine `MULTI_SUBJECT_DOCTRINE` (section « Méthode de travail », toutes cibles, littéral stable) :
  - (a) plusieurs sujets : décomposer ; sujets indépendants ⇒ une délégation par sujet émise dans le MÊME tour (`explore` pour lire, `task` pour écrire là où il est proposé) ; sujets dépendants ⇒ dans l'ordre ; synthèse finale qui rend compte de chaque sujet.
  - (b) interjection : un message `[Interjection de l'utilisateur — …]` s'AJOUTE ; poursuivre le travail en cours ; indépendant ⇒ le déléguer en parallèle, sinon le traiter après ; la réponse finale couvre toutes les demandes ; seule une consigne explicite (« arrête », « laisse tomber », « à la place ») remplace.
- `SYNTHESIS_PROMPT` : « réponds à TOUTES les demandes de ce tour (la première comme les précisions et interjections) ».
- « Choix des outils » : la ligne « GROUPE… » dit vrai (lectures en parallèle ; écritures dans l'ordre émis) ; la ligne `task` ne décourage plus la délégation quand il y a plusieurs sujets indépendants.
- Cap (frontend) : 2 suites cochées et plus ⇒ `Traite ces suites — en parallèle celles qui sont indépendantes : 1) … ; 2) …`.

## Cas limites / erreurs
- Délégation fermée (`max-delegations: 0`) : la doctrine reste vraie (elle dit « quand ils sont proposés ») ; aucun outil absent n'est annoncé par « Choix des outils ».
- Cible SANDBOX : pas de `task` ; la doctrine parle d'`explore` et d'agir dans l'ordre.
- Interjection « à la place » : remplace (doctrine explicite).

## Critères d'acceptation
1. La doctrine est présente sur SANDBOX et RUNNER, identique à l'octet entre deux constructions.
2. `SYNTHESIS_PROMPT` contient « TOUTES les demandes ».
3. La ligne `task` de « Choix des outils » mentionne « un `task` par sujet » ; la ligne GROUPE ne promet plus un parallélisme indistinct.
4. Cap : 2 cochées ⇒ phrase « Traite ces suites — en parallèle celles qui sont indépendantes : 1) … ; 2) … » ; 1 cochée ⇒ inchangé.

## Plan de test
- Unitaires backend (`AtelierChatServiceSystemPromptTest`) : présence SANDBOX/RUNNER, stabilité octet, synthèse, ligne task.
- Unitaires/intégration frontend : `terminal-next-steps.component.spec.ts`, `terminal-et-maintenant.spec.ts` mis à jour.
- Isolation utilisateur : sans objet (aucune donnée lue ni écrite).

## Impacts
- `AtelierChatService` (constantes + assemblage), `terminal-next-steps.component.ts`. Aucun endpoint, aucune table.
- Préoccupations transversales : aucune.

## Hors périmètre
- Exécution réellement parallèle (SF-195-02/03/04), budget par sujet (SF-195-05).
