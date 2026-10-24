# Cadrage F-208 — Le tableau des chantiers

**Demande PO du 2026-10-24** — deux besoins du registre (F-205), le second complétant le premier :
- `d3fedd1d` « Panneau visuel des chantiers guidés : états des étapes, mise à jour live, focus et pilotage par étape » ;
- `4d3d6a89` « Overlay des chantiers : avancement visuel (phases, étapes, sous-étapes, preuves) et boutons d'action ».

Livraison autonome. Justesse avant coût ; charte `DESIGN_SYSTEM.md` (aucune couleur nouvelle, tokens existants) ; préfixe système stable (rien de volatil dans la consigne).

## Besoin (mots du PO)
« Un genre d'overlay qui s'affiche avec les chantiers, le chantier en cours, et les différentes étapes […] que ce soit très joli […] quand il y a un amendement, cette page-là va se mettre à jour […] voir les étapes qui ont été faites […] dans telle étape il y a tel step qui a été fait […] il y a des boutons. » « Ce n'est pas vraiment une page, c'est vraiment un overlay. » « Tu peux me challenger. »

## Existant vérifié (origin/main 4f130b16)
- Plan = `JourneyPlan` (JSON `plan_json`) : `Step(title, risk LECTURE|NOTES|REVERSIBLE|EXTERNE, verify, rollback, waitsOn, status A_FAIRE|FAIT|VERIFIE|ECHEC, evidence)`. **Pas de sous-étapes, pas de dépendances entre étapes** (`waitsOn` = une attente humaine F-175).
- **Plafond `MAX_STEPS = 20`** (`JourneyPlan` l.27) : les étapes au-delà sont **ignorées en silence** — cause du tour 211 où des étapes faites n'ont pas pu être marquées.
- **Historique des amendements écrasé** : seuls le plan courant et le dernier validé existent ; un événement `PLAN_AMENDED` « vN · K étapes ».
- `GET /journey` ne détaille le plan **que du chantier au focus** ; les autres n'ont que `done/total`.
- Aucun événement SSE du parcours : l'écran se rafraîchit **en fin de tour** (`loadJourney`).
- Seul panneau existant : `terminal-journey-chantiers` (chantiers **clos**, en ligne, pas un overlay). Bande du parcours `terminal-journey-strip`, barre d'état F-207 (`terminal-dock`).
- Parallélisme (F-195) : seules les **lectures** et bash classé lecture seule partent en parallèle ; une action non-lecture est toujours sérialisée par le harnais. ⇒ « deux étapes EXTERNE jamais en même temps » est **déjà garanti** par la boucle — à préserver et à tester, pas à reconstruire.

## Décisions (arbitrées par défaut, réversibles)
1. **Plafond** : 20 → **40 étapes** ; au-delà, l'outil **refuse avec un message au modèle** (plus jamais d'ignorance silencieuse). La note de tour garde sa borne de 3 000 caractères (préfixe/contexte maîtrisé) — l'overlay, lui, montre tout.
2. **Dépendances d'étapes** : champ facultatif `depends_on` (rangs d'étapes **antérieures**, validés) dans `set_subject_plan`. Une étape est **débloquée** si toutes ses dépendances sont FAIT/VERIFIE et son attente (`waits_on`) n'est pas ouverte ; sans `depends_on`, règle historique : les étapes précédentes. Le champ sert aussi F-209 (prérequis). Aucune dépendance n'est jamais devinée.
3. **Sous-étapes** : nouvel outil agent `update_sub_steps(step, sub_steps[{title, done, evidence}], chantier?)` qui **remplace** la liste des sous-étapes d'une étape (idempotent, ≤ 12 sous-étapes, titres ≤ 200). Une étape A_FAIRE avec au moins une sous-étape faite est affichée **« en cours »** (état dérivé, pas de nouvel état stocké). Les amendements reportent sous-étapes et dépendances (`carryProgress`).
4. **Historique des versions** : table `subject_journey_plan_versions` (migration **166**) — une ligne par `set_subject_plan` (user_id, workspace_id, chantier_number, version, plan_json, created_at). Route `GET /journey/chantiers/{n}/versions` : versions avec **ce qui a changé** par rapport à la précédente (étapes ajoutées / retirées / modifiées), calculé côté serveur. Isolation `user_id` + `requireOwned`.
5. **Tous les chantiers ouverts détaillés** : `GET /journey` rend le plan complet de chaque chantier ouvert (`chantiers[].plan`) avec, par étape, `unlocked`, `blockedBy` (dépendances non faites / nom de l'attente), `subSteps`, `dependsOn`.
6. **Direct** : événement SSE `journey` émis après chaque outil du parcours appliqué pendant le tour (plan posé/amendé, étape, sous-étapes, diagnostic) ⇒ l'overlay se recharge **pendant** le tour.
7. **Overlay** (pas une page) : bouton « Chantiers » dans la barre d'état F-207 (et entrée du menu de l'en-tête), **badge** = nombre de gestes attendus de l'utilisateur (plan à valider, diagnostic, clôture, attente ouverte liée à une étape). Panneau en surimpression (`position: fixed`, modèle `terminal-actions-panel`), fermeture Échap / clic dehors, plein écran sur téléphone. Contenu par chantier (onglets, focus en premier) :
   - frise des phases (Investigation → Plan → Exécution → Vérification → Clos), phase courante en évidence ; anneau « 5/14 » ;
   - liste **complète** des étapes : icône + couleur par état (fait, vérifié, en cours, bloqué + nom de l'attente/des dépendances, à faire, échec), risque, « après 4, 6 » ;
   - étape dépliable : sous-étapes cochées avec preuve, vérification, retour arrière ; l'étape en cours est dépliée d'office ;
   - historique « v1 … v14 » repliable avec le résumé des changements.
8. **Boutons** :
   - **« Faire cette étape »** : actif seulement si l'étape est débloquée ; étape EXTERNE ⇒ **confirmation explicite** ; envoie une demande pré-remplie **visible dans le fil** (« Chantier N — fais l'étape M : titre ») ; si un tour tourne, part en **interjection** (route steer F-195).
   - **« Voir la preuve »**, **« Relancer l'attente »** (demande pré-remplie de relance de l'attente nommée), **« Mettre au focus »** (route existante `POST /chantiers/{n}/focus`).
   - Pilotage de plusieurs chantiers : « Faire cette étape » sur un chantier non focus vise ce chantier (paramètre `chantier` des outils, F-206) ; les garde-fous de parallélisme restent ceux du harnais (point « Existant »).

**Challenge PO (réponse à « tu peux me challenger »)** : pas de bouton « exécuter tout le plan » ni de parallélisme forcé entre chantiers — l'agent garde le pilotage du rythme, la porte F-176 et la sérialisation des actions non-lecture restent les garde-fous ; le bouton donne une **intention visible**, il ne contourne rien.

## Préoccupations transversales
- **Contexte tenant** : nouvelle table et nouvelle route ⇒ filtre `user_id` + `requireOwned`, test d'isolation (404 sur le terminal d'autrui).
- **Navigation** : aucun changement de route Angular (overlay dans le terminal).
- Porte `JourneyGate` : non modifiée — tests existants à garder verts.

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-208-01 | Modèle du plan : plafond 40 + refus explicite, `depends_on`, sous-étapes + outil `update_sub_steps`, report à l'amendement, note de tour, GUIDE ; tests | 🟠 |
| SF-208-02 | Historique des versions (migration 166, route versions + diff), plan détaillé de tous les chantiers ouverts (`unlocked`, `blockedBy`), événement SSE `journey` ; tests (isolation) | 🟠 |
| SF-208-03 | Front : overlay des chantiers (bouton + badge, frise, anneau, étapes, sous-étapes, preuves, versions, rafraîchissement direct) | 🟠 |
| SF-208-04 | Front : boutons d'action (Faire cette étape + confirmation EXTERNE + interjection, Voir la preuve, Relancer l'attente, Mettre au focus) | 🟠 |

## Hors périmètre
Édition du plan à la main dans l'overlay ; réordonnancement par glisser ; exécution automatique d'étapes sans demande visible.
