# Mini-spec — F-239 / SF-239-02 — Une salutation ne se délègue jamais (serveur)

## Identifiant
`F-239 / SF-239-02` · Feature parente `F-239` « Jarvis entend juste » · Statut `in-review` · 2026-11-02 ·
Branche `feat/SF-239-02-salutation-jamais-deleguee`

## Objectif
> Le cerveau rapide répond lui-même et brièvement à une salutation ou une politesse, ne conclut plus par « Je vous
> écoute », ne dit plus l'annonce creuse « Je vérifie, Monsieur. », et la passerelle garde la trace (sans contenu)
> des paroles refusées par l'écran.

## Comportement attendu
### Cas nominal
- **Consigne v3** (`JarvisFastBrainPrompt.VERSION = "3"`, texte fixe, rien de volatil) : formules interdites seules
  **ou en fin de réponse** (« Je vous écoute », « À votre écoute », « Je reste à votre disposition », « N'hésitez
  pas »…) ; section « Salutations et politesse » : une phrase courte, sans bilan ni point de la journée non demandé,
  jamais `deleguer` ; exemple « Bonjour Jarvis. » ⇒ « Bonjour, Monsieur. ».
- **Garde d'invitation** (`isListeningInvitation`) : une phrase qui commence par une invitation à parler est retirée,
  seule ou en conclusion (compteur `fillersRemoved`).
- **Salutation** (`isSmallTalk`, liste fermée normalisée : bonjour, bonsoir, merci, ça va, au revoir, ok…, « Jarvis »
  ignoré) :
  - une phrase **non ancrée** est **retirée** (compteur `groundingTrips`), sans délégation ;
  - si la décision reste une délégation (outil appelé, refus, délai, erreur, réponse vide, texte d'outil) ⇒ réponse
    **ANSWER** : ce qui a déjà été dit de sûr, sinon une **réponse courtoise fixe** (`courtesyReply` : « Bonjour,
    Monsieur. », « Bonsoir, Monsieur. », « Avec plaisir, Monsieur. », « Bonne journée, Monsieur. », « Fort bien,
    merci, Monsieur. », « Oui, Monsieur ? »), poussée au fil de l'eau ;
  - un repli (plafond, quota) reste un repli.
- **Annonce d'office** (`AUTO_ANNOUNCE`) : vide (silence côté écran, SF-239-01) ; l'annonce d'une délégation forcée
  par « vérifie » devient « Je vérifie. » (elle répond à la demande).
- **Question du sujet** : quand une délégation sans sujet devient « Pour quel client ou quel sujet, Monsieur ? »,
  la question est désormais poussée au flux (elle ne l'était pas).
- **Trace des paroles refusées** : `POST /api/jarvis/timings` accepte `rejectedKind` (liste fermée : empty, noise,
  hallucination, no_voice, implausible) et `rejectedChars` ; colonnes `rejected_kind`, `rejected_chars` de
  `jarvis_turn_timings` ; jamais le texte.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `rejectedKind` hors liste | Refus de la mesure | 400 |
| `rejectedChars` sans `rejectedKind` | Longueur ignorée (null) | 204 |
| `rejectedChars` négatif ou énorme | Borné à [0, 10 000] | 204 |
| Salutation et plafond du cerveau rapide atteint | Repli (mode actuel), inchangé | — |

## Critères d'acceptation
- [ ] « Bonjour Jarvis. » avec un bilan non ancré (cas prod 08:11:52) ⇒ ANSWER « Bonjour Monsieur. », aucune délégation.
- [ ] « Bonjour Jarvis » que le modèle délègue, « Merci Jarvis » en panne ⇒ ANSWER courtoise, dite.
- [ ] « … Je vous écoute pour la suite de la journée. » ⇒ phrase retirée.
- [ ] Délégation d'office : annonce vide ; « vérifie » : « Je vérifie. ».
- [ ] Mesure : raison et longueur gardées, raison hors liste ⇒ 400, isolation `user_id` (aucune ligne chez autrui).
- [ ] Consigne : version 3, identique d'un appel à l'autre, sans contenu volatil (tests existants).

## Périmètre — hors scope
Écran (SF-239-01) ; fin de tour, transcription, correction (SF-239-03→06) ; un « point de la journée » spontané en
début de journée (option laissée de côté : le point n'est donné que sur demande).

## Contraintes de validation
| Champ | Obligatoire | Valeurs | Normalisation |
|---|---|---|---|
| `rejectedKind` | Non | empty, noise, hallucination, no_voice, implausible | insensible à la casse |
| `rejectedChars` | Non | entier | borné [0, 10 000], ignoré sans `rejectedKind` |

## Technique
- Endpoint modifié : `POST /api/jarvis/timings` (champs facultatifs ajoutés, rétrocompatible).
- Table : `jarvis_turn_timings` + `rejected_kind varchar(16)`, `rejected_chars int` — migration
  `239-jarvis-turn-timings-rejected.xml` (additive, rollback = retrait des colonnes).
- Code : `JarvisFastBrainPrompt`, `JarvisFastGuards`, `JarvisFastBrainService`, `JarvisTurnTiming`,
  `JarvisLatencyService`.

## Plan de test
- Unitaires : `JarvisFastGuardsTest` (invitations, salutations, réponses courtoises) ; `JarvisFastBrainServiceTest`
  (salutation non ancrée, salutation déléguée / en panne, repli conservé, invitation en conclusion, annonce d'office
  vide, question du sujet poussée).
- Intégration : `JarvisLatencyApiIntegrationTest` (trace des refus, 400 hors liste, isolation).
- Isolation `user_id` : la mesure d'un compte n'apparaît jamais chez un autre (test).

## Dépendances
SF-239-01 (mergée). Aucune question ouverte.

## Notes et décisions
- La liste des salutations est fermée et identique à celle de l'écran (`isSmallTalk`).
- La consigne change ⇒ nouvelle entrée de cache (version 3), une fois.
