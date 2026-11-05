# Mini-spec — F-247 / SF-247-11 Opus en mode Jarvis, poste éteint

## Identifiant
`F-247 / SF-247-11`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.5 « Opus en mode Jarvis »)

## Statut
`done` (2026-11-05) · **Estimation :** 0,75 j · **Branche :** `feat/SF-247-11-opus-poste-eteint` · **Dépend de :** SF-247-10

## Objectif
Un tour Opus né de Jarvis sur un sujet dont le poste est hors ligne reçoit, dans son message, l'état connu de ce
poste — au lieu de finir en « poste hors ligne » sans rien dire.

## Comportement attendu

### Cas nominal
1. `JarvisStateNote.appendTo(userId, workspace, message, presentation)` : si le poste du sujet est **hors ligne** au
   départ du tour, le bloc « État connu » **préparé** (SF-247-10, aucune requête au registre) est joint **au message**,
   précédé d'une note fixe (v1) : outils du poste indisponibles, faits datés par la passerelle, à dire avec leur date,
   jamais comme actuels ; une action attendra le retour du poste.
2. Branché dans `AtelierChatService`, pour les tours nés à la voix, juste après le portrait — sur le patron de
   `JarvisOralNote`. **La consigne système du terminal ne change pas.**

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Poste connecté, présentation, sujet sans poste, bloc vide, coupe-circuit | message identique à l'octet |
| Statut du poste illisible | message identique |

## Critères d'acceptation
- [x] Poste éteint : note + bloc après le message.
- [x] Sinon : message identique à l'octet (`isSameAs`).
- [x] Consigne système du terminal intacte (aucune modification de la consigne ni des outils du terminal).

## Plan de test
- Unitaires : `JarvisStateNoteTest` (2).
- Non-régression : suites `AtelierChat*` (suite complète).

## Impact
- Backend : `jarvis/state/JarvisStateNote` (nouveau) ; `AtelierChatService` (setter facultatif + un appel dans la branche `viaJarvis`).
- Préoccupations transversales : Contexte tenant — `RunnerStatusService.hostStatus(userId, hostId)` (404 hors compte), bloc du compte.

## Hors périmètre
Les pancartes des faits de la note (F-248 SF-08).
