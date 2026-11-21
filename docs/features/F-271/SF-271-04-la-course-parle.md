# Mini-spec — [F-271 / SF-271-04] La course parle à Jarvis

## Identifiant
`F-271 / SF-271-04`

## Feature parente
`F-271` — Jarvis lance le travail (cadrage `CADRAGE-F-271-jarvis-lance-le-travail.md`, D6, maillon M4, scénario S5)

## Statut
`in-progress`

## Date de création
2026-11-21

## Branche Git
`feat/SF-271-04-la-course-parle`

---

## Objectif
Le PO sait sans aller au terminal où en est une course lancée : sa fin, son blocage (avec ce qui manque), son échec ou
son arrêt sont dits par Jarvis à la pause ; « où en est LDIC-325 ? » est répondu depuis l'état.

## Comportement attendu

### Cas nominal
1. `GET /api/jarvis/works` : chaque course autonome porte `course` = `{stepsDone, stepsTotal, status, reason, byUser}` —
   `status` brut (`RUNNING`, `DONE`, `PARTIAL`, `BLOCKED`, `FAILED`, `STOPPED`), `reason` = la raison d'arrêt en **une
   phrase** (≤ 160 caractères, sans Markdown), `byUser` = l'utilisateur a repris la main (raison « Reprise en main. »).
   Les délégations et tours directs : `course` absent (`null`).
2. L'écran relit les travaux (toutes les 4 s tant qu'un travail tourne, F-270). Une course **vue en cours** puis finie
   est dite **une fois**, dans la file des annonces (à la pause, jamais par-dessus le PO, F-235 / F-270) :
   - `DONE` : « LDIC-325 est fini. »
   - `BLOCKED` : « LDIC-325 est bloqué : il manque le droit IAM sur le compte data-ingestion dev. »
   - `FAILED` : « LDIC-325 s'est arrêté en échec : <raison>. »
   - `PARTIAL` (jusqu'au blocage) : « LDIC-325 a avancé jusqu'au blocage : trois étapes sur six. »
   - `STOPPED` (par la passerelle) : « LDIC-325 est arrêté, trois étapes sur six. »
3. Rien n'est dit : course reprise en main par l'utilisateur (`byUser`), course arrêtée depuis l'écran (« arrête
   LDIC-330 » a déjà dit « J'arrête LDIC-330. »), course déjà finie au premier regard (écran rouvert).
4. L'avancement d'une étape n'est **pas** dit (scène seulement, SF-271-05) ; la progression se dit sur demande.
5. **« Où en est LDIC-325 ? »** : le contexte du cerveau rapide reçoit un bloc « Courses autonomes en cours » (chantier,
   titre à clé, sujet, étapes faites / total, départ) — **omis** quand aucune course ne tourne (contexte inchangé à
   l'octet dans le cas courant). Dans le message, jamais dans la consigne système.
6. Jarvis fermé : la notification existante `AUTONOMY_DONE` / `AUTONOMY_STOPPED` (F-219) — inchangée.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Lecture des travaux en échec | rien n'est dit (la course sera vue à la relecture suivante si encore visible) |
| Raison d'arrêt absente | phrase sans raison (« LDIC-325 est bloqué. ») |
| Raison longue ou en Markdown | première phrase, ≤ 160 caractères, marques retirées |
| Course d'un autre compte | jamais lue (filtre `user_id`, inchangé) |

## Critères d'acceptation
- [ ] `course` rendu pour une course (progression, issue, raison courte, `byUser`) ; absent ailleurs ; Bob ne lit rien.
- [ ] Fin / blocage / échec / partiel / arrêt : phrase juste, dite une fois, à la pause.
- [ ] Reprise en main, arrêt depuis l'écran, course déjà finie au premier regard : rien n'est dit.
- [ ] Bloc « Courses autonomes en cours » dans le contexte rapide quand une course tourne, absent sinon.
- [ ] Banc à vraie voix 2 passages : pas de régression.

## Périmètre

### Hors scope (explicite)
- Constellation des étapes, teinte du cœur : SF-271-05.
- Événement « étape franchie » dit à voix haute (D6 : scène seulement).
- Liste d'outils et consigne système du cerveau rapide : inchangées.

## Valeurs initiales
| Paramètre | Valeur |
|---|---|
| Raison dite | ≤ 160 caractères, une phrase |
| Fin visible côté passerelle | 20 s (F-270, inchangé) ; relecture 4 s |

## Contraintes de validation
Aucune saisie.

## Technique

### Endpoint(s)
`GET /api/jarvis/works` : champ `course` ajouté (rétrocompatible).

### Tables impactées
`chantier_autonomy_runs` (lecture seule).

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
- `jarvis/jarvis-works.ts` : `JarvisCourse`, `courseEndPhrase`, `CourseWatch`.
- `jarvis/jarvis.component.ts` : `tellEndedCourses`, `stoppedHere`.

### Composants backend
`JarvisWorks` (`Course`, `shortReason`), `JarvisWorksService`, `JarvisFastContextBuilder.courses`,
`AutonomyTurnRunner.TAKEN_BACK` (public).

## Plan de test

### Tests unitaires
- `JarvisWorksTest.shortReason` ; `jarvis-works.spec.ts` (`courseEndPhrase`, `CourseWatch`).
- `jarvis.component.spec.ts` : blocage dit une fois ; arrêt depuis l'écran non redit.
- `JarvisFastContextBuilder` : bloc présent / absent.

### Tests d'intégration
- `JarvisWorksIntegrationTest` : `course` (progression, raison, reprise en main), isolation Bob.

### Isolation workspace
- Même classe : Bob ne lit aucune course d'Alice.

## Dépendances

### Subfeatures bloquantes
SF-271-03 (mergée) ; F-270 SF-01/04/05 (en prod).

### Questions ouvertes impactées
Aucune.

## Préoccupations transversales
Aucune.

## Notes et décisions
- **D1** Les fins sont détectées par l'écran à la relecture (état en base, identique depuis tout pod) plutôt que poussées
  par un bus : même choix que F-270 / SF-270-01 (D2).
