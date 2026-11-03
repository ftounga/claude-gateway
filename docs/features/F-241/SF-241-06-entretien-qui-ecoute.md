# Mini-spec — F-241 / SF-241-06 L'entretien qui écoute

## Identifiant
`F-241 / SF-241-06`

## Feature parente
`F-241` — Jarvis vous connaît : le portrait (cadrage §4 D13, D14, D16) — correctif de SF-241-02 après l'essai réel du PO.

## Statut
`in-progress`

## Date de création
2026-11-03

## Branche Git
`feat/SF-241-06-entretien-qui-ecoute`

---

## Objectif
Que l'entretien oral laisse le PO répondre longuement et ne prenne jamais un réveil ou une salutation pour une réponse.

## Origine — essai réel du PO (prod, Android/PC, mains libres) : « une catastrophe »
1. **Coupé** : la fin de tour (1,2 s, +1,5 s sur phrase suspendue) close la réponse au premier silence de réflexion, et Jarvis passe à la question suivante.
2. **Veille en plein entretien** : au retour, « Bonjour Jarvis » était pris pour une réponse ; l'entretien continuait en silence et Jarvis « répondait à côté ».

---

## Comportement attendu

### Cas nominal
- **E1 — fin de tour d'entretien (choix : silence long, pas de relance systématique)** : pendant un entretien **actif**, la réponse ne se clôt qu'après **3,5 s** de silence (`INTERVIEW_END_MS`), mesuré à l'écran et appliqué aussi à la fin de tour du fournisseur (le « commit » du flux ne clôt plus seul : il arme une attente de 3,5 s, relancée dès que la parole reprend). Le réglage local du silence ne peut que l'allonger. Hors entretien : inchangé (1,2 s / +1,5 s).
- **E2 — segments concaténés, jamais perdus** : un segment clos par le plafond d'une écoute (30 s) n'est pas une réponse finie : il est gardé, l'écoute rouvre sans changer de question, le segment suivant s'y ajoute. Juste après le passage à la question suivante, « attendez », « je n'avais pas fini », « je continue », « laissez-moi finir » rouvrent la réponse précédente (« Je vous écoute. ») : la parole suivante s'y ajoute, puis Jarvis repose la question en cours.
- **E3 — réveil et salutation jamais comptés** : pendant l'entretien, « Jarvis », « Bonjour Jarvis », « Salut Jarvis », « Tu es là ? » (liste fermée, phrase entière) ne sont jamais une réponse : Jarvis repose la question en cours. « Jarvis, pause » / « mets-toi en veille » suspendent l'entretien (E4) et ne sont pas une réponse.
- **E4 — suspension** : la pause (bouton ou voix), la mise en veille au silence (`endConversation`), l'écran masqué (`visibilitychange` → `hidden`) **suspendent** l'entretien : écoute coupée, réponses et segment en cours gardés, aucune question dite.
- **E5 — reprise jamais implicite** : au réveil (mot d'éveil, « Reprendre la conversation », toucher de l'invite), si un entretien est suspendu, Jarvis ne reprend **pas** : il dit « Nous en étions à la question n sur N de l'entretien. On reprend l'entretien ? » et écoute **sans** la pré-écoute du réveil (« Bonjour Jarvis » n'est jamais transcrit comme réponse). Réponse :
  - « oui », « on reprend », « reprenons » ⇒ reprise à la question en cours (redite) ;
  - réveil / salutation / bruit ⇒ la question de reprise est reposée (« On reprend l'entretien ? Oui ou non. ») ;
  - « non » ⇒ « Entendu. » ; toute autre phrase ⇒ traitée **normalement** (cerveau rapide…) ; dans les deux cas l'entretien est clos et les réponses déjà données vont au **brouillon** à relire (rien d'enregistré avant « Valider ») ; sans réponse, rien.
- **E6 — visible et arrêtable au toucher** : pendant un entretien, un bandeau « Entretien — question n/N » (ou « Entretien en pause — question n/N ») avec **Arrêter** (réponses gardées pour le brouillon) et, suspendu, **Reprendre**. Jamais en présentation (il n'y a pas d'entretien en présentation).
- **E7 — D16 et proposition** : « mettons à jour mon portrait » reste possible à tout moment hors entretien ; la **proposition** (D13) ne revient plus dès qu'un entretien a été validé (`last_interview_at` non nul), même si le portrait est vidé section par section ensuite (« Tout effacer » supprime la ligne : on repart de zéro, la proposition peut revenir).
- **E8 — le micro « n'entend pas » (élargissement demandé, cause n° 1 mesurée en prod)** : sur l'essai du PO, 6 paroles réelles sur 10 ont été **refusées par la porte de parole** (`jarvis-speech-gate.ts`) : 4 `implausible` (62 à 119 caractères, `speech_ms` 5 à 12 s), 1 `no_voice` (23 caractères), 1 `empty`. Cause : le temps de voix (`voicedMs`) ne comptait que les trames au-dessus du seuil fixe `SPEECH_LEVEL` — sous-compté sur un micro à gain bas / contrôle automatique de gain — et était remis à 0 en cours d'écoute. Correction, objectif anti-hallucination de SF-239-01 gardé :
  - `implausible` se juge sur la **durée de l'écoute** (`speech_ms`) : > 6 mots/s + 4 mots ⇒ débit physiquement impossible ;
  - `no_voice` seulement si le micro n'a **presque rien** entendu (< 100 ms de voix) **et** phrase très courte (≤ 4 mots) ;
  - le temps de voix se compte au-dessus d'un **seuil adaptatif** au bruit de fond (`NoiseFloor` : 2,5 × bruit + 0,015, borné entre 0,03 et `SPEECH_LEVEL`) ;
  - la remise à zéro du temps de voix et du début d'écoute (silence en conversation) n'a lieu **que** si aucune voix (seuil adaptatif) n'a été entendue dans la fenêtre — jamais au milieu d'une parole ;
  - niveau non mesuré ou phrase du réveil : règles de voix inchangées (non appliquées) ; hallucinations connues (« Sous-titres réalisés par… ») toujours refusées par leur texte.

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Rangement en échec à l'arrêt / à l'abandon | « Le rangement de l'entretien a échoué ; rien n'a été enregistré. » | — |
| Écran quitté (composant détruit) pendant l'entretien | entretien perdu, rien enregistré (aucune persistance avant « Valider ») | — |
| Silence de plus de 60 s pendant une question | veille ⇒ suspension (E4), jamais une réponse vide | — |
| Non authentifié (`GET /interview`) | refus | 401 |

---

## Critères d'acceptation
- [ ] CA1 — une réponse en plusieurs segments (plafond d'écoute, « je n'avais pas fini ») arrive au brouillon en **une** réponse concaténée, dans l'ordre.
- [ ] CA2 — pendant un entretien actif, la fin de tour vaut 3,5 s (écran et fournisseur) ; hors entretien elle est inchangée.
- [ ] CA3 — veille ⇒ réveil ⇒ « Bonjour Jarvis » : aucune réponse comptée, Jarvis demande « On reprend l'entretien ? ».
- [ ] CA4 — « oui » reprend à la question en cours ; « non » ou une autre demande clôt l'entretien, réponses au brouillon, la demande est traitée normalement.
- [ ] CA5 — « Jarvis », « Bonjour Jarvis », « Jarvis, pause » ne sont jamais enregistrés comme réponse.
- [ ] CA6 — le bandeau « Entretien — question n/N » est visible avec « Arrêter » ; lisible à 360 px sans débordement.
- [ ] CA7 — `GET /interview` rend `offer: false` après un entretien validé, même portrait vidé ; « mettons à jour mon portrait » lance toujours la mise à jour.
- [ ] CA8 — 18 mots en 8,6 s d'écoute avec un temps de voix bas passent la porte ; « Sous-titres réalisés par… » dans le silence est refusé ; 20 mots en 2 s restent `implausible`.

---

## Périmètre

### Hors scope (explicite)
- Consigne du cerveau rapide et bancs (SF-240-05 en parallèle) — **non touchés**.
- Persistance d'un entretien inachevé entre deux ouvertures de l'écran.
- Liste des questions modifiable ; relance « Autre chose ? » après chaque réponse (écartée, voir Notes).

## Valeurs initiales
| Valeur | Défaut | Justification |
|---|---|---|
| `INTERVIEW_END_MS` | 3 500 ms | voir Notes D1 |
| fenêtre « je n'avais pas fini » | jusqu'à la parole suivante | la réponse précédente n'est rouvrable qu'une fois, juste après la question suivante |

## Contraintes de validation
| Champ | Obligatoire | Max | Règle |
|---|---|---|---|
| réponse concaténée | — | 2 000 car. (borne serveur SF-241-02) | tronquée à 2 000 côté écran avant l'envoi au rangement |

## Technique

### Endpoint(s)
| Méthode | URL | Changement |
|---|---|---|
| GET | `/api/jarvis/portrait/interview` | `offer` faux si `last_interview_at` non nul |

### Tables impactées
`jarvis_portraits` (lecture de `last_interview_at`, existante). Aucune nouvelle table.

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
`jarvis-interview.ts` (segments, réouverture, suspension, listes fermées réveil/reprise), `jarvis-speech-gate.ts` (E8 : débit sur la durée d'écoute, `NoiseFloor`), `JarvisComponent` (fin de tour d'entretien, suspension sur pause/veille/écran masqué, reprise au réveil, bandeau), `jarvis.component.html/.scss`.

### Préoccupations transversales
- [x] Navigation / routing — aucune route. Le routage de la parole dans `handle()` ne change que **pendant un entretien** (actif ou suspendu) : réveil/salutation, pause, réponse de reprise. Composants vérifiés : `handle()` (confirmations, preuves, corrections, « dis-m'en plus », pièces, pause, sujet, cerveau rapide — inchangés hors entretien), `onWake`/`wakeUpOn` (pré-écoute écartée **seulement** si un entretien est suspendu), `toggleConversation`/`wakeUp`, `pauseConversation`, `endConversation`, boucle `openListening` (fin de tour), `holdIfSuspended`.
- [x] Contexte tenant — `JarvisInterviewService.offer(userId)` : `repository.findById(userId)` inchangé, seule la règle lit `last_interview_at` de la même ligne.

## Plan de test

### Tests unitaires
- `jarvis-interview.spec.ts` : réponse longue en segments concaténés ; « je n'avais pas fini » rouvre la précédente ; « Bonjour Jarvis »/« Jarvis » ignorés ; suspension garde le segment ; listes fermées reprise (`isResumeYes`, `isWakeOnly`) ; « on arrête là » garde le segment en cours.
- `jarvis.component.spec.ts` : segment plafonné ⇒ pas de question suivante, concaténé ; veille ⇒ réveil ⇒ « Bonjour Jarvis » non compté, « On reprend l'entretien ? » ; « oui » ⇒ reprise à la question en cours ; autre phrase ⇒ entretien clos, brouillon, demande traitée normalement ; bandeau + Arrêter ; fin de tour 3,5 s en entretien.
- `JarvisInterviewServiceTest` inchangé (règle pure).
- `jarvis-speech-gate.spec.ts` (E8) : cas mesurés en prod (18 mots / 8,6 s / voix basse acceptés ; 119 car. / 12 s ; 62 car. / 5 s ; 23 car. à voix faible) ; anti-hallucination (sous-titres dans le silence, débit impossible) ; `NoiseFloor` (voix faible comptée, souffle non, plafond au seuil fixe).

### Tests d'intégration
- `JarvisInterviewApiIntegrationTest` : après validation (`interviewed: true`), `offer` faux ; portrait vidé ⇒ toujours faux.

### Isolation workspace
- Aucune nouvelle donnée ; l'offre se lit sur la ligne de l'utilisateur courant (`currentUser.requireId()`). Le test d'isolation de SF-241-02 (A jamais dans l'appel de B) reste vert.

## Dépendances

### Subfeatures bloquantes
SF-241-02 (mergée).

### Questions ouvertes impactées
Aucune.

## Notes et décisions
- **D1 — 3,5 s, pas de relance « Autre chose ? »** : le banc `--vad` (SF-239-10) montre que 1,2 s coupe encore 28/52 phrases à pause de commande courte ; une réponse d'entretien est un récit (« votre parcours… »), avec des pauses de recherche de mots de 2 à 3 s. 3,5 s couvre ces pauses avec une marge, et reste sous le seuil (~4 s) où le silence devient gêne. Une relance systématique « Autre chose ? » doublerait les échanges (20 au lieu de 10) et sonnerait comme un formulaire : le Jarvis du film laisse parler, puis enchaîne. Le filet pour la pause plus longue est E2 (« attendez, je n'avais pas fini »). Réversible (une constante).
- **D2 — la reprise écoute sans pré-écoute** : le réveil « Bonjour Jarvis » est une phrase ; sans entretien suspendu, elle reste transmise (F-239). Avec un entretien suspendu, la question de reprise passe d'abord : la phrase du réveil n'a pas à être traitée.
- **D3 — « non » à la reprise garde le brouillon** : les réponses déjà données ne sont pas perdues (demande du PO) ; elles s'ouvrent à la relecture, et rien n'est enregistré sans « Valider ».
- **D4 — proposition close par l'entretien validé** : la règle D13 « portrait sans contenu » laissait revenir la proposition après un « tout effacer » ; `last_interview_at` la ferme. La mise à jour reste à la voix (D16).
