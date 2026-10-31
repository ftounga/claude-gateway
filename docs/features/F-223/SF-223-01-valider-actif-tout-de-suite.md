# SF-223-01 — « Valider » actif tout de suite, carte du plan bornée

**Feature :** F-223 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-223-01-valider-actif-tout-de-suite`

## Objectif
Les boutons du parcours (Valider, Valider sans lancer, Planifier, Clore…) sont cliquables dès que la carte s'affiche, même pendant le tour qui l'a posée.

## Constat (cadrage F-223, bug 1)
Les boutons sont liés à `[busy]="journeyBusy() || submitting"` : ils restent grisés jusqu'à la fin du tour (30 s à 1 min), pendant que l'agent rédige encore sa présentation (« présente-le » de `JourneyToolExecutor`).

## Comportement
1. **Bande du parcours** : `busy` = seulement l'envoi du geste (`journeyBusy`), plus le tour en cours.
2. **Geste avec reprise pendant un tour** (« Valider le plan et lancer », « Planifier et continuer », « Clore ») : le geste part tout de suite ; le message de reprise (« ✓ Plan v2 validé — exécution lancée. ») est **différé** et envoyé dès la fin du tour (une seule fois). « Valider sans lancer » ne relance rien.
3. **Pourquoi c'est grisé** : pendant l'envoi du geste, la carte dit « Envoi du geste… » ; pendant un tour, l'indice dit « Un tour est en cours : la validation part tout de suite, l'exécution démarrera à la fin du tour. »
4. **Consigne `set_subject_plan`** (résultat d'outil, pas le préfixe) : « présente-le en une phrase au plus, puis termine ton tour ».
5. **Carte bornée** : la liste des étapes défile à l'intérieur (au plus ~26 vh), la rangée d'actions reste visible ; plan **replié par défaut** quand l'écran est bas (hauteur ≤ 760 px), dépliable.

## Erreurs
| Situation | Comportement |
|---|---|
| Le geste échoue (réseau, 409) | snackbar « Le parcours du sujet n'a pas pu être mis à jour. », aucune reprise différée |
| Changement de projet avant la fin du tour | la reprise différée est abandonnée (jamais envoyée dans un autre sujet) |
| Fin de tour mais un autre tour repart aussitôt | la reprise reste différée jusqu'à la vraie fin |
| Lecture seule ou plafond atteint | aucune reprise (inchangé) |

## Critères d'acceptation
- [x] Pendant un tour, « Valider le plan et lancer » est actif ; le clic appelle `…/journey/plan/validate` tout de suite.
- [x] À la fin du tour, le message de reprise est envoyé une fois (brouillon conservé).
- [x] « Valider sans lancer » pendant un tour ne démarre rien.
- [x] Pendant l'envoi du geste, les boutons sont grisés avec « Envoi du geste… ».
- [x] La liste des étapes a une hauteur bornée avec défilement ; actions hors de la zone défilante.
- [x] Écran bas : plan replié par défaut.

## Plan de test
- Spec `terminal-journey-resume.spec.ts` : validation pendant un tour → requête immédiate, 0 envoi ; fin du tour → 1 envoi avec le message ; changement de projet → 0 envoi ; validate-only pendant un tour → 0 envoi.
- Spec strip : boutons actifs avec `busy=false` et `turnRunning=true`, indice affiché ; `busy=true` → « Envoi du geste… ».
- Backend : consigne vérifiée par test unitaire `JourneyToolExecutor` (texte « une phrase au plus »).
- Isolation : aucun accès données nouveau (endpoints existants, `requireOwned`).

## Impact
`atelier-terminal.component.(html|ts)`, `terminal-journey-strip.component.ts`, `JourneyToolExecutor`. Préoccupations transversales : aucune (pas d'auth, de tenant, de quota, de route).

## Hors périmètre
Contrastes (SF-223-02) ; validation écrite (F-222).
