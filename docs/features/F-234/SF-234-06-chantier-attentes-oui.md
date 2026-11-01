# Mini-spec — F-234 / SF-234-06 — S9 chantier, S11 attentes, S2 oui et question, S14 points de route

## Identifiant
`F-234 / SF-234-06`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage §5, §8.2, D14 ; vague B, familles 3, 4 et 5 du §2 : chantier 564,
questions 270, attentes 254 appels sur 30 j)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-06-chantier-attentes-oui`

---

## Objectif

> Le chantier qui avance, les attentes posées et levées, le « oui » attendu et les points de route du tour ont leur
> scène dédiée, déduite des étiquettes passerelle (SF-234-04) et des événements déjà diffusés.

## Comportement attendu

- **Registre** : `update_plan_step`, `update_sub_steps`, `set_subject_plan`, `set_plan`, `valider_plan`,
  `exit_plan_mode`, `submit_diagnosis`, `confirmer_diagnostic`, `reopen_investigation`, `propose_guided_mode`,
  `lancer_chantier_autonome` → `S9` ; `record_blocker`, `update_blocker`, `close_blocker` → `S11` ; `demander` → `S2` ;
  `decouper_demande`, `suivre_question` → `S14`.
- **S9 — L'étape qui avance** : l'étiquette « chantier 3 · étape 2 · faite » est lue ; le chantier est rapproché de
  l'univers (dernier sujet cité, puis sujet courant) et s'allume ; la scène dessine le losange, « Ch. 3 — titre », la
  **phase** (F-176), la barre `done/total` et l'étape qui se **coche** (faite / vérifiée). Les étapes faites pendant
  le tour avancent le compteur **avant** la relecture de l'univers (fin de tour), puis l'univers relu fait foi.
  Le plan du tour (`plan`, F-39) : « Le plan se pose · n étapes ». Phase (diagnostic, confiance), autonomie : écrites.
- **S11 — Attente posée / levée** : `record_blocker` « Zahi · validation » ⇒ le carré de la personne s'attache au
  chantier (trait pointillé), la personne s'allume ; la carte `attente` (F-175) ou `close_blocker` ⇒ levée (trait
  rompu, nom barré). Pose, mise à jour, levée et carte forment **une** scène.
- **S2 — Le oui attendu** : `confirm_request` ⇒ devant toute autre scène, **compte à rebours** (`timeoutMs`), « Dites
  « oui » — sinon je ne fais rien » ; à l'échéance elle expire seule ; `confirm_resolved` ⇒ la scène d'avant
  reprend. `question_request` (F-164) ⇒ variante « J'ai une question pour vous — réponse à l'écran du terminal »
  jusqu'à `question_resolved`.
- **S14 — Points de route** : l'événement `questions` (F-210) ⇒ une puce par question (≤ 6, 48 car.), vide / à
  moitié (en cours) / pleine (traitée) / barrée (non aboutie), « Points de route · 1/3 », mise à jour en place.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Étiquette sans numéro de chantier, ou chantier inconnu | étape écrite, sans losange (S11 : chantier au focus du sujet courant) |
| `record_blocker` sans personne | catégorie seule, « Quelqu'un » |
| `confirm_request` sans `timeoutMs` | aucun compte à rebours (rien d'inventé) |
| Liste de questions vide | rien |

## Critères d'acceptation
1. `update_plan_step` étiqueté ⇒ étape cochée en cours de tour, `done/total` 2/5 → 3/5 ; univers relu ⇒ compteur de l'univers.
2. `record_blocker` ⇒ personne attachée au chantier ; `close_blocker` / carte FAIT ⇒ détachée.
3. `confirm_request` ⇒ S2 devant toute autre scène jusqu'à `confirm_resolved` ou expiration.
4. `questions` ⇒ une puce par question.

## Plan de test
- `jarvis-scenes-chantier.spec.ts` : registre, S9 (étiquette, compteur, remise à zéro), plan et phase, S11 (pose,
  levée, catégorie seule), S2 (priorité, compte à rebours, expiration, question), S14 (mise à jour) ; rendu S9 / S2 / S11.
- Non-régression : suite `jarvis/**`, garde `JarvisSceneRegistryGuardTest`, `npm run build`.

## Impacts
- **Tables / endpoints** : aucun. **Back** : aucun.
- **Front** : registre, lecteur (`plan`, `attente`, `question`, `questions`, compte à rebours), fil, scène,
  `jarvis.component.ts` (relais `onPlan`, `onAttente`, `onQuestions`, `onQuestionResolved`, `timeoutMs`).

## Préoccupations transversales
Aucune (rien ne part au modèle ; aucune route ; aucune lecture nouvelle).

## Arbitrages (réversibles)
- Personne vs catégorie d'une étiquette à un seul mot : une capitale initiale = une personne (étiquette passerelle).
- Le compteur `done/total` avance en local sur « faite »/« vérifiée », puis l'univers relu fait foi.
- S2 garde le même liseré orange ; la question structurée est une variante de S2 (une seule scène qui demande).

## Hors périmètre
Liste complète des étapes d'un chantier (l'univers n'en porte que la courante, D4) ; répondre à la question depuis
Jarvis (F-164 reste au terminal).
