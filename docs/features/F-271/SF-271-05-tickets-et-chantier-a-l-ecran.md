# Mini-spec — [F-271 / SF-271-05] Tickets et chantier à l'écran

## Identifiant
`F-271 / SF-271-05`

## Feature parente
`F-271` — Jarvis lance le travail (cadrage `CADRAGE-F-271-jarvis-lance-le-travail.md`, D7, D8, D9 ; scénarios S1, S3-S6)

## Statut
`in-progress`

## Date de création
2026-11-21

## Branche Git
`feat/SF-271-05-tickets-et-chantier-a-l-ecran`

---

## Objectif
Sans une phrase écrite, l'écran Jarvis montre les tickets dont parle une fin de travail (pancartes), le chantier en cours
comme une constellation d'étapes à noms courts, et le cœur prend en halo la couleur du travail (or quand il exécute).

## Comportement attendu

### Cas nominal
1. **D7 — pancartes après un tour délégué** : quand la fin d'un travail en fond est **dite** (file des annonces), les
   clés techniques qu'elle cite (« LDIC-325 et LDIC-330 sont prêts, LDIC-331 attend Adam ») sont demandées **au premier
   mot** au registre des pancartes (F-248, `GET /api/jarvis/cards?keys=`), comme pour une ligne 🎙 d'un tour direct ;
   seules celles connues (faits `JIRA:*` extraits du tour délégué, F-247) s'affichent. Réglage « pancartes » de l'appareil
   et présentation respectés (inchangé).
2. **Constellation du chantier** : `GET /api/jarvis/works` rend pour chaque travail sur un chantier (numéro connu, ou clé
   de ticket d'un **seul** chantier ouvert) ses **étapes** : `{name, state}` — nom court (`StepShortName` : sigle
   « SNS »/« IAM »/« MR », sinon nom propre « Terraform », sinon deux mots sans le verbe de tête « Test dev » ; ≤ 14
   caractères), état `DONE` (fait, vérifié), `BLOCKED` (bloqué, échec), `CURRENT` (première à faire d'un travail qui
   exécute), `TODO` ; 12 au plus.
3. L'écran montre **un** chantier : le travail vivant le plus récent qui a des étapes, sinon le plus récent fini encore
   visible (le plan vient d'être posé). Ses étapes sont des **étoiles** sur un arc sous le cœur, de gauche à droite,
   reliées d'un trait fin : faites vert, en cours **or** (bat), bloquées rouge (clignote), à faire creuses. Un nom court
   par étoile, aucune phrase.
4. **D8 — la couleur du travail** : le bouton du cœur porte `data-work` = la phase la plus forte des travaux en cours
   (`strongestPhase`, F-270) ; un **halo** derrière le cœur en prend la teinte — exécute or `#E8B44A`, attend votre oui
   cyan, réfléchit / en attente violet `#9B7BFF` — et respire (plus vite quand il exécute). Aucun travail : aucun halo.
   Le premier plan (écoute, parole) reste celui du cœur.
5. **D9 — téléphone** (< 600 px) : satellites allégés (F-270), **pas** de constellation ; le halo reste. Présentation :
   ni satellites, ni constellation, ni halo.
6. Mouvement réduit : halo et étoiles figés.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Plan illisible, chantier clos, sujet d'un autre compte | aucune étape (lecture sous `user_id`, best-effort) |
| Clé citée par deux chantiers ouverts | aucune étape pour ce travail |
| Lecture des pancartes en échec / aucune pancarte connue | rien ne s'affiche, la phrase est dite (inchangé) |
| Plus de 12 étapes | les 12 premières |

## Critères d'acceptation
- [ ] `steps` rendu (nom court, état ; en cours pour une course) ; par clé de ticket aussi ; Bob ne voit rien.
- [ ] `StepShortName` : SNS, IAM, Terraform, Test dev, MR ; ≤ 14 caractères.
- [ ] Constellation : le bon chantier, noms courts, teinte par état ; aucune sur téléphone ni en présentation.
- [ ] Halo : `data-work=executing` pendant une course ; absent sans travail.
- [ ] Fin annoncée citant des tickets ⇒ pancartes demandées.
- [ ] Captures PC 1920×1080 et téléphone 390×844 relues (aucune phrase à l'écran).
- [ ] Banc à vraie voix 2 passages : pas de régression.

## Périmètre

### Hors scope (explicite)
- Ordres d'écran « 🖥 montrer » : intouchés. Panneaux latéraux : chantier séparé.
- Rendu 3D dédié des étoiles (couche légère au-dessus du cœur, en 2D comme en 3D, comme les satellites F-270).
- Champ « nom court » demandé à Opus dans `set_subject_plan` (le nom est calculé côté passerelle : aucun outil changé).

## Valeurs initiales
| Paramètre | Valeur |
|---|---|
| Étoiles max | 12 |
| Nom court | ≤ 14 caractères |
| Arc | de 160° à 20°, rayon 78 % du cœur |

## Contraintes de validation
Aucune saisie.

## Technique

### Endpoint(s)
`GET /api/jarvis/works` : champ `steps` ajouté (rétrocompatible). `GET /api/jarvis/cards` (F-248) : inchangé.

### Tables impactées
`subject_journeys`, `subject_journey_chantiers` (lecture seule).

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
- `jarvis/jarvis-chantier-steps.component.ts/.scss` (neuf) : `featuredChantier`, `starsOf`, rendu.
- `jarvis/jarvis-works.ts` : `JarvisStep`.
- `jarvis/jarvis.component.*` : halo `data-work`, constellation, pancartes des annonces.

### Composants backend
`StepShortName` (neuf), `JarvisWorks.Step`/`StepState`, `JarvisWorksService.withSteps`, `JarvisWorksController`.

## Plan de test

### Tests unitaires
- `StepShortNameTest` ; `jarvis-chantier-steps.component.spec.ts` ; `jarvis.component.spec.ts` (halo, constellation,
  téléphone, pancartes d'une annonce).

### Tests d'intégration
- `JarvisWorksIntegrationTest` : étapes d'une course (DONE, CURRENT, TODO) et d'un tour direct par clé ; isolation.
- Captures réelles (Chrome sans tête) PC et téléphone.

### Isolation workspace
- `JarvisWorksIntegrationTest` : Bob ne lit rien d'Alice.

## Dépendances

### Subfeatures bloquantes
SF-271-01 (titre à clé, `openChantiersWithTicket`), SF-271-04 (`course`).

### Questions ouvertes impactées
Aucune (teinte « exécute » = or, défaut du cadrage non objecté).

## Préoccupations transversales
Aucune.

## Notes et décisions
- **D1** Nom court calculé côté passerelle plutôt que demandé à Opus : aucun outil ni consigne changés (préfixe intact).
- **D2** Les étoiles sont une couche légère au-dessus du cœur (comme les satellites F-270) : un seul rendu 2D/3D.
