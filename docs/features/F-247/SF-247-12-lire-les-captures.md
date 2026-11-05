# Mini-spec — F-247 / SF-247-12 Lire les captures

## Identifiant
`F-247 / SF-247-12`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.6, D13, D14, D16, porte Z1b)

## Statut
`done` (2026-11-05) · **Estimation :** 2 j (réestimé 1 j : file durable, rappel F-162 et interlocuteurs F-180 existent) ·
**Branche :** `feat/SF-247-12-lire-les-captures` · **Dépend de :** SF-247-02, SF-247-03

## Objectif
À la réception d'une capture de conversation déposée dans l'Atelier, la passerelle la fait lire **une fois**, hors du
chemin du dépôt, par le modèle rapide via l'abstraction fournisseur, et garde la transcription datée et nommée par
elle-même — l'image n'est jamais gardée.

## Comportement attendu

### Cas nominal
1. `WorkspaceDepositService.deposit` : après l'enregistrement du dépôt, un crochet facultatif `DepositReceivedListener`
   reçoit le sujet, l'identifiant du dépôt et les octets. `CaptureReadEnqueuer` ne retient que les **images reconnues
   par leurs octets** (png, jpeg, webp), d'au plus 5 Mo, d'un sujet rattaché à un client (`host_id`), et les confie à un
   **fil de remise** (file bornée à 50) qui insère `CAPTURE_READ` dans `async_jobs` (image dans `payload_bytes`). La
   réponse du dépôt part sans attendre.
2. `CaptureReadJobHandler` (1 fil, plafond de 200 en attente) relit le sujet **sous le `user_id` du travail**, vérifie le
   réglage du client (`enabled`, `captures_enabled`), le plafond de **60 lectures/jour**, le plafond propre de
   **10 €/mois** (`app.jarvis.state.capture-cap-eur`) et l'arrêt dur du budget Jarvis, puis fait **un seul appel**
   (`AiAgentProvider`, modèle rapide, consigne fixe `capture-v1`, bloc `image`, 1 500 jetons de sortie au plus).
3. Garde `CaptureReadParser` : JSON strict (`conversation`, `plateforme` ∈ teams/mail/autre, 1 à 60 messages,
   interlocuteur ≤ 120 car., texte ≤ 2 000 car.). Non conforme ⇒ `ILLISIBLE`, rien gardé ; `conversation: false` ⇒
   `NON_CONVERSATION`, rien gardé (coût compté).
4. **La passerelle fixe** : client et sujet (ceux du dépôt) ; la **date** de chaque message (`CaptureTimeResolver` :
   « Hier 14:32 », « lundi 10:05 », « 04/11 08:10 », « 3 nov. 16:40 », « 2:15 PM », ISO… à partir de l'heure du dépôt,
   jamais dans le futur ; sans heure lisible ⇒ heure de la bulle précédente ou du dépôt, marquée « vers ») ; la
   **personne** (`CaptureSpeakerResolver` : nom et variantes des interlocuteurs du client, « Nom, Prénom », mentions
   entre parenthèses ; ambigu ⇒ nom lu gardé).
5. Rangement : `jarvis_conversation_captures` (une ligne par lecture, statut, coût) + `jarvis_capture_lines` (texte
   **recopié**, en clair). Un dépôt n'est jamais lu deux fois. Abonnés aux lignes écrites (vectorisation, SF-247-13).
6. **L'image s'efface** : la file met `payload_bytes` à `null` dès la fin du travail, réussi, abandonné ou échoué.
7. Coût : journal `usage_turns` (origine `JARVIS`, part `CAPTURE`, hors quota Gateway) et `jarvis_usage_days.capture_cost_usd`,
   compté dans le total du budget Jarvis F-244 (`Costs.state`). Au plafond propre : arrêt, notification
   `JARVIS_CAPTURES_CAP` **une fois par mois**, jamais d'escalade.
8. Couper le client ou « effacer tout » purge ses transcriptions (abonné `JarvisStateRegistry.onErase`) ; purge
   nocturne au-delà de la rétention (90 j par défaut, `capture_retention_days`).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Fichier non image (octets), > 5 Mo, sujet sans client, coupe-circuit `APP_JARVIS_STATE_CAPTURES_ENABLED=false` | aucun travail |
| File de remise pleine / pod tombé avant l'insertion | capture perdue, comptée (`rejected`) |
| Sujet d'un autre compte | `DROPPED no_client`, aucun appel |
| Client coupé / captures coupées | `DROPPED disabled` |
| 60 lectures du jour | `DROPPED daily_cap` |
| Plafond propre ou budget Jarvis dur | `DROPPED cost_cap`, une notification par mois |
| Fournisseur en échec | échec réessayé une fois (recul), puis `FAILED` ; image effacée |
| Sortie non conforme / pas une conversation | rien gardé, coût compté |

## Contraintes de validation
5 Mo par image ; 60 lectures/jour ; 10 €/mois ; 60 messages ; 2 000 car. par texte ; 120 par nom ; 300 par titre ;
rétention 1-365 j (90 par défaut).

## Critères d'acceptation
- [x] Un seul appel par capture, par `AiAgentProvider`, bloc `image`, consigne fixe.
- [x] Dates et personnes fixées par la passerelle (banc CAPTURES 10/10) ; texte recopié à l'identique.
- [x] Rien gardé en cas de doute ; un dépôt jamais lu deux fois.
- [x] Image effacée de la file à la fin du travail.
- [x] Porte Z1b : 100 remises en < 200 ms avec une file bloquée.
- [x] Isolation `user_id` + `host_id` ; couper efface.
- [x] Coût au budget Jarvis, plafond propre, notification unique.

## Plan de test
- Unitaires : `CaptureBenchTest` (4 : banc CAPTURES 10 cas, garde, dates, homonymes).
- Intégration : `CaptureReadJobIntegrationTest` (6 : lecture, rejeu, isolation, rien gardé, bornes + coupure, image
  effacée, porte Z1b).
- Non-régression : `Push*`, `NotificationPreferences*`, `JarvisCost*`, `JarvisBudget*`, `*Deposit*`, `JarvisState*`,
  `StateExtract*`, `JarvisUsage*`.

## Impact
- Migration `247-12-jarvis-conversation-captures.xml` : `jarvis_conversation_captures`, `jarvis_capture_lines`,
  `jarvis_usage_days.capture_cost_usd` / `notes_cost_usd`.
- Backend : `atelier/deposit/DepositReceivedListener` (nouveau) + crochet dans `WorkspaceDepositService` ;
  `jarvis/state/capture/*` (nouveau) ; `jarvis/state/JarvisStateSpend` (nouveau, partagé avec SF-247-14) ;
  `JarvisStateRegistry.onErase` ; `JarvisUsageDay`/`JarvisUsageService`/`JarvisUsageDayRepository`/`JarvisCostService`
  (part `state`) ; `UsageTurn.JarvisPart` (`CAPTURE`, `ETAT`) ; `PushEvent` (`JARVIS_CAPTURES_CAP`, `JARVIS_NOTES_CAP`).
- Préoccupations transversales :
  - **Contexte tenant** : `WorkspaceRepository.findByIdAndUserId` (relecture du sujet), `JarvisStateRegistry.settingsOf`,
    `InterlocuteurRepository.findByUserIdAndHostIdOrderByDisplayNameAsc`, `JarvisCaptureStore` (toute requête sous
    `user_id` + `host_id`), crochet de dépôt appelé après `requireOwned`.
  - **Plans / limites** : `JarvisBudgetService.hardStop`, `JarvisCostService.Costs.state` (total du budget),
    `UsageLedgerService.recordAnnex` (tag `JARVIS/CAPTURE`, hors quota Gateway).

## Arbitrages (réversibles)
- Le type d'image est décidé par les **octets**, jamais par le type annoncé.
- Une bulle sans heure prend l'heure de la bulle précédente, marquée « vers » (plus juste que l'heure du dépôt).
- Notification « une fois par mois » adossée au premier travail `DROPPED cost_cap` du mois (trace durable, multi-pod).
- Tables manipulées en JDBC (la colonne vectorielle de SF-247-13 n'est pas mappable en JPA).

## Hors périmètre
Le rattrapage des captures antérieures ; la vectorisation, `recall` et l'outil `conversations` (SF-247-13) ; l'écran
(SF-247-15 lot 2) ; le banc CAPTURES sur de vraies images (mesure en direct, précondition de déploiement).
