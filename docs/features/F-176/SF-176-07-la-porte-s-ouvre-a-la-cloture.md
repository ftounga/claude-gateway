# Mini-spec — F-176 / SF-176-07 — La porte s'ouvre à la clôture

## Identifiant
`F-176 / SF-176-07` — rouverture du 2026-10-06 (`CADRAGE-F-176-rouverture-2026-10-06.md`, D1→D5), validée PO.
Branche : `feat/SF-176-07-porte-s-ouvre-a-la-cloture`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Qu'un chantier clos **libère** le terminal (retour en Libre, porte ouverte) et que la porte du mode
guidé ne bloque jamais l'authentification du poste ni une lecture évidente — défaut G3 prouvé en prod
(11 refus après clôture, dont `aws sso login`).

## Comportement attendu

### Cas nominal
1. **Clore = revenir en Libre** (D1) : `close()` passe `phase=CLOS` **et** `mode=LIBRE`. L'événement
   `CLOSED` est journalisé (mesure). Le plan et le diagnostic restent lisibles dans la réponse.
2. **La porte ne s'applique qu'en Guidé actif** (D2) : `JourneyGate.refusal` rend `null` hors de
   `GUIDE` + phase ∈ {Investigation, Plan, Exécution, Vérification}. En `CLOS`, plus aucun refus.
3. **Reprise des lignes existantes** : migration `146` — `UPDATE subject_journeys SET mode='LIBRE'
   WHERE mode='GUIDE' AND phase='CLOS'`.
4. **Authentification du poste = NOTES** (D3) : `aws sso login|logout`, `aws configure sso`,
   `az login|logout`, `az account set`, `gcloud auth login|application-default login`,
   `gcloud config set project`, `gh auth login|logout|refresh|switch`, `glab auth login`,
   `kubectl config use-context|set-context --current` → classe `NOTES` (toujours libre).
   Une commande mêlant auth et modification garde la classe de la modification.
5. **Lectures évidentes** (D4) : préfixes de shell sans effet hors du processus (`export X=…`,
   `set -e`, `set -o pipefail`, `unset X`) et lecteurs compressés (`zgrep`, `zless`, `bzcat`,
   `xzcat`) sont des lectures. Un refus dû à un **programme inconnu** est journalisé
   `… · inconnu` (jamais la commande) pour mesurer la prudence.
6. **Message exact** (D5) : tout refus commence par
   « Ce terminal est en mode Guidé, phase X : cette action attend la validation du plan. »
   suivi de la consigne par phase pour l'agent.
7. **Une seule source pour le verrou** (D5) : la réponse du parcours porte `gateClosed` (booléen) et
   `gateMessage` calculés par `JourneyGate` ; le front affiche le verrou **ssi** `gateClosed`.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `close` sur un sujet non guidé | 400 « Ce sujet n'est pas en mode guidé. » (inchangé) |
| `aws sso login && terraform apply` | `EXTERNE` (la modification l'emporte) — refusée hors Exécution |
| parcours illisible pendant le tour | Libre (porte ouverte), inchangé |
| journal indisponible | le refus s'applique quand même |

## Contraintes de validation
Aucune saisie nouvelle. `gateMessage` ≤ 200 caractères (phrase fixe).

## Critères d'acceptation
- [ ] Clore un parcours → `mode=LIBRE`, `phase=CLOS` ; `aws sso login` et `terraform apply` ne sont plus refusés.
- [ ] En Guidé · Plan : `aws sso login` passe (NOTES) ; `terraform apply` est refusé avec le message D5.
- [ ] La réponse dit `gateClosed=true` en Guidé · Plan, `false` en Libre et après clôture.
- [ ] Le verrou du front suit `gateClosed` (et rien d'autre).
- [ ] Une ligne existante `GUIDE`+`CLOS` est reprise en `LIBRE`.
- [ ] Le menu « Guidé » après clôture rouvre en Investigation (comportement gardé ; nouveau chantier = SF-176-11).

## Plan de test
- **Unitaires** : `JourneyRiskClassifierTest` (auth = NOTES, auth + apply = EXTERNE, préfixes `export`/`set`,
  porte ouverte en CLOS, message D5, `isClosed`, inconnu).
- **Intégration** : `SubjectJourneyApiIntegrationTest` (close → LIBRE + `gateClosed=false` ; Plan → `gateClosed=true`).
- **Boucle** : `AtelierChatServiceJourneyTest` (journal « inconnu »).
- **Isolation** : inchangée (`requireOwned` en premier, test Bob existant).
- **Front** : `terminal-journey.spec.ts` (verrou ssi `gateClosed`).

## Impacts
- Migration `146-subject-journeys-clos-libre.xml` (données uniquement, rollback = no-op documenté).
- Backend : `SubjectJourneyService.close`, `JourneyGate`, `JourneyRiskClassifier`, `SubjectJourneyResponse`,
  `AtelierChatService.journeyGateRefusal` (détail « inconnu »).
- Front : `journey.models.ts`, `terminal-journey-strip.component.ts`.
- Aucun nouvel endpoint.

### Préoccupations transversales
- Auth / tenant / plans / navigation : **non** (aucun changement d'identité ; `user_id` + `workspace_id` inchangés).

## Hors périmètre
Repli de la bande après clôture (SF-176-08), relance du tour au clic (SF-176-09), indicateur « à qui la
main » (SF-176-10), nouveau chantier à la réouverture (SF-176-11).
