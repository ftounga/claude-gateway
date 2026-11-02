# Mini-spec — F-241 / SF-241-02 L'entretien oral : premier entretien et mise à jour à la demande

## Identifiant
`F-241 / SF-241-02`

## Feature parente
`F-241` — Jarvis vous connaît : le portrait (cadrage §4 D13, D14, D16 ; §6 SF-241-02)

## Statut
`in-progress`

## Date de création
2026-11-02

## Branche Git
`feat/SF-241-02-entretien-oral`

---

## Objectif
Remplir le portrait à la voix, puis le mettre à jour, sans rien enregistrer avant la validation écrite du PO.

---

## Comportement attendu

### Cas nominal
- **Proposition (D13)** : à l'ouverture de `/jarvis`, hors présentation, l'écran demande `GET /api/jarvis/portrait/interview` ; si `offer` (portrait sans contenu, < 3 propositions, la dernière il y a ≥ 7 jours), une carte affiche « Puis-je vous poser une dizaine de questions pour mieux vous connaître ? Cinq minutes. » avec **Commencer** / **Plus tard**. Un « oui » dit répond aussi ; « non » / « Plus tard » ⇒ `POST /interview/offered` (la suivante attend 7 jours). Pas pendant une délégation ni un tour en cours.
- **Parcours (sur l'appareil)** : dix questions D14 dites par la voix existante ; chaque parole est une réponse ; « je passe » ⇒ réponse vide ; « on arrête là » ⇒ fin, réponses déjà données gardées. Pendant l'entretien, **aucune** parole ne part au cerveau rapide, au fil, à la mémoire du jour, ni à la capture du banc.
- **Rangement** : à la fin, `POST /api/jarvis/portrait/interview/draft {answers:[{question, answer}]}` — **un** appel au cerveau rapide via `AiAgentProvider` (consigne figée, outil `ranger_portrait`), coût inscrit au journal Jarvis. Rend `{sections, souvenirs}` (souvenirs d'origine `ENTRETIEN`) ; **n'écrit rien**. Fournisseur en panne ⇒ brouillon brut (chaque réponse dans la section de sa question).
- **Relecture** : le volet « Mon portrait » s'ouvre avec le brouillon surligné (sections proposées, souvenirs proposés) ; **Valider** ⇒ `PUT /api/jarvis/portrait {sections, interviewed: true}` puis `POST /souvenirs` (source `ENTRETIEN`) ; **Abandonner** ⇒ rien.
- **Mise à jour à la demande (D16)** : « mettons à jour mon portrait », « mets à jour mon portrait », « refaisons l'entretien » (liste fermée sur l'appareil) ⇒ d'abord chaque section / souvenir « à revoir » (> 12 mois), puis « Quoi de neuf côté travail ? », « Et côté vie personnelle ? », « Autre chose à retenir ? » ; même relecture.

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Corps absent / `answers` absent | refus | 400 |
| > 20 réponses ou réponse > 2 000 caractères | refus explicite | 400 `invalid_portrait` |
| Fournisseur en panne | brouillon brut, aucune écriture | 200 |
| Rangement en échec côté écran | « Le rangement de l'entretien a échoué ; rien n'a été enregistré. » | — |
| Présentation | ni proposition, ni entretien | — |
| Non authentifié | refus | 401 |

---

## Critères d'acceptation
- [ ] CA1 — un entretien complet mène à un brouillon rangé ; rien n'est en base avant « Valider ».
- [ ] CA2 — « je passe » laisse la section vide (la réponse vide n'est pas envoyée au modèle).
- [ ] CA3 — « Abandonner » ne laisse aucune trace (ni fil, ni mémoire du jour, ni capture, ni base).
- [ ] CA4 — la mise à jour commence par les éléments « à revoir ».
- [ ] CA5 — aucune proposition en présentation ni pendant une délégation ou un tour.
- [ ] CA6 — le brouillon de A n'est jamais rendu à B (le portrait de A n'entre pas dans l'appel de B).

---

## Périmètre — hors scope
- Retenir en conversation (SF-241-05) ; usage du portrait par Jarvis (SF-241-03).
- Liste des questions modifiable par le PO (goût, à recueillir à l'essai).

## Contraintes de validation
| Champ | Obligatoire | Max | Règle |
|---|---|---|---|
| `answers` | Oui | 20 | `question` ≤ 400, `answer` ≤ 2 000 ; vides ignorées |
| brouillon `souvenirs` | — | 10 | ≤ 200 car., sections connues seulement |

## Technique
| Méthode | URL | Auth |
|---|---|---|
| GET | `/api/jarvis/portrait/interview` | compte |
| POST | `/api/jarvis/portrait/interview/offered` | compte |
| POST | `/api/jarvis/portrait/interview/draft` | compte |
| PUT | `/api/jarvis/portrait` (+ `interviewed`) | compte |

Tables : `jarvis_portraits` (`interview_offers`, `interview_offered_at`, `last_interview_at` — colonnes de SF-241-01). Aucune migration.
Composants : `jarvis-interview.ts` (parcours, listes fermées, client), `JarvisPortraitComponent` (+ brouillon), `JarvisComponent` (proposition, routage de la parole, exclusion de la capture).

### Préoccupations transversales
- [x] Navigation / routing — aucune route ; la parole est routée vers l'entretien **avant** tout autre traitement de `handle()` seulement pendant un entretien ; hors entretien, seuls « oui/non » à la carte visible et la liste fermée D16 sont interceptés (hors présentation, hors confirmation en attente). Composants vérifiés : `handle()` (confirmations, preuves, corrections, « dis-m'en plus », pièces, arrêt, changement de sujet, cerveau rapide) — inchangés en dehors de ces cas.
- [x] Contexte tenant — `JarvisInterviewService` (`currentUser.requireId()`, `repository.findById(userId)`, `portraits.snapshot(userId)`).

## Plan de test
- Unitaires : `JarvisInterviewServiceTest` (cadence 7 j / 3 fois, lecture bornée du brouillon, repli brut, bornes) ; `jarvis-interview.spec.ts` (dix questions, passer, arrêter, reprise, « à revoir » d'abord).
- Intégration : `JarvisInterviewApiIntegrationTest` (fournisseur bouchonné : brouillon, **aucune ligne en base**, validation datée ; panne ⇒ brouillon brut ; 400 ; cadence ; 401).
- Isolation : le portrait de A n'apparaît pas dans l'appel de B.
- Écran : `jarvis.component.spec.ts` (+2 : proposition hors présentation, « oui » ⇒ questions, réponses jamais envoyées au cerveau ni à Opus, brouillon ouvert ; « non » ⇒ remise) ; `jarvis-portrait.spec.ts` (+2 : valider, abandonner).

## Dépendances
SF-241-01 (mergée). Aucune question ouverte.

## Notes et décisions
- **A4 (réversible)** : la proposition est une **carte visible** (et un « oui » dit), pas une phrase dite d'office à l'ouverture : l'audio peut être bloqué avant le premier toucher et une phrase spontanée couvrirait la première demande du PO.
- **A5 (réversible)** : `GET /interview` et `POST /interview/offered` ajoutés (la cadence D13 vit côté serveur) ; la validation passe par le `PUT` existant (`interviewed: true`), pas par un point d'accès dédié.
