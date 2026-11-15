# Mini-spec — [F-261 / SF-261-01] Clore un chantier et une attente sur la parole écrite de l'utilisateur

## Identifiant
`F-261 / SF-261-01`

## Feature parente
`F-261` — Clore sur la parole de l'utilisateur

## Statut
`in-progress`

## Date de création
2026-11-15

## Branche Git
`feat/SF-261-01-clore-un-chantier`

---

## Objectif
Sur une demande écrite et explicite de l'utilisateur dans le tour, l'agent clôt un chantier vérifié (`cloturer_chantier`) ou ferme une attente (`close_blocker` + `user_words`), comme un clic — jamais de sa propre initiative.

## Comportement attendu

### Cas nominal
- `cloturer_chantier {chantier: 7, user_words: "tu peux cloturer"}` dans un tour d'utilisateur où il a écrit « Il a validé tu peux cloturer et fermer » ; chantier 7 en Vérification, toutes étapes `VERIFIE` → même geste que le bouton [Clore] (`SubjectJourneyService.close`) : chantier archivé `CLOS`, focus recalculé, Libre si c'était le dernier. Événement `CLOSED` avec détail `chantier 7 · écrit « … »`. Résultat : « Chantier 7 : clos sur ta demande… ».
- `close_blocker {key, reason, user_words}` avec citation vérifiée → l'attente passe à `FAIT` (ou `ANNULE` si `cancelled`), `closed_reason` = `reason` sinon la citation ; la proposition éventuelle est effacée. Carte du fil `CLOSED` (« Clos sur ta demande »).
- `close_blocker` sans `user_words` : inchangé (proposition [Clore] [Garder]).

### Cas d'erreur (résultat d'outil en erreur, jamais d'exception ; rien ne change)
| Situation | Comportement |
|---|---|
| `chantier` absent | « chantier est requis… » |
| `user_words` absent (cloturer_chantier) | « user_words est requis… ne clos pas » |
| Tour sans utilisateur (course autonome, routine, contexte inconnu) | refus « personne n'y écrit » |
| Citation absente de ce que l'utilisateur a écrit pendant ce tour | refus « ces mots ne figurent pas… » |
| Chantier pas en Vérification, plan vide ou étape non vérifiée | refus : « seules les étapes toutes vérifiées se closent par écrit ; sinon le bouton » |
| Chantier inconnu / clos / d'un autre terminal | refus lisible (« Le chantier N n'est pas ouvert. ») |
| close_blocker : `user_words` cités mais invalides ou tour sans utilisateur | refus, l'attente reste telle quelle |
| close_blocker : attente déjà fermée / inconnue | messages existants |

## Critères d'acceptation
- [ ] CA1 — fil du chantier 7, « clos le chantier » écrit dans le tour, 15 étapes vérifiées → le chantier passe à Clos sans clic (test d'intégration équivalent).
- [ ] CA2 — sans citation, citation inventée, ou tour headless : refus, chantier inchangé.
- [ ] CA3 — chantier en Exécution (ou Vérification avec une étape non vérifiée) : refus, chantier inchangé.
- [ ] CA4 — le journal `CLOSED` porte `écrit « … »` pour l'outil ; le bouton reste inchangé.
- [ ] CA5 — `close_blocker` avec `user_words` vérifiés ferme l'attente ; sans, il propose comme avant ; citation invalide → refus et attente intacte.
- [ ] CA6 — isolation : le chantier visé est celui du terminal du tour (user_id + workspace du tour, aucun identifiant lu dans l'outil) ; une attente d'un autre compte/poste est introuvable.
- [ ] CA7 — préfixe stable : définitions et guides sont des littéraux ; le test « système identique » existant reste vert ; `cloturer_chantier` figure dans `JourneyToolCatalog.NAMES` et `toolsFor`.

## Périmètre — hors scope
- Clôture écrite d'un chantier hors Vérification (en sommeil, Exécution) : bouton seul.
- Durcissement de `confirmer_diagnostic` (sa citation n'est pas vérifiée) — signalé dans la PR.
- Aucune nouvelle route HTTP, aucune migration.

## Valeurs initiales
Non applicable (aucune entité créée).

## Contraintes de validation
| Champ | Obligatoire | Longueur max | Format | Normalisation |
|---|---|---|---|---|
| chantier | Oui (cloturer_chantier) | — | entier ≥ 1 ou « 7 » | chiffres extraits |
| user_words | Oui (cloturer_chantier) ; facultatif (close_blocker) | journal tronqué à 120 car. | doit figurer mot pour mot dans un texte de l'utilisateur du tour | casse, accents, ponctuation ignorés (`JourneyToolExecutor.quoted`) |
| reason (close_blocker) | Non | 300 (existant) | texte | strip |

## Technique
- Endpoints : aucun nouveau.
- Tables : `subject_journeys` / `subject_journey_chantiers` (UPDATE via `close` existant), `subject_journey_events` (INSERT `CLOSED`, détail), `terminal_actions` (UPDATE statut/raison).
- Migration : non applicable.
- Backend : `JourneyToolCatalog` (définition + guide + NAMES), `JourneyToolExecutor.closeChantier`, `SubjectJourneyService.closeOnUserWords`, `TerminalActionToolCatalog` (param + guide), `TerminalActionToolExecutor.close(…, TurnContext)`, `TerminalActionService.closeOnUserWords`, `AtelierChatService.applyRecordBlocker` (contexte du tour), `JourneyTurnNote` (message, non système).
- Frontend : `attente-card` — titre/icône du genre `CLOSED`.

### Préoccupations transversales
- Auth / Principal : non. Contexte tenant : non (userId/workspace viennent du tour, comme les autres outils). Plans/limites : non (même garde d'espace Forge/Vigie). Navigation : non.

## Plan de test
### Unitaires
- `TerminalActionToolTest` : close_blocker avec user_words vérifiés → FAIT ; invalides → erreur, intacte ; headless → erreur ; sans → proposition (existant).
- `JourneyToolCatalogGuideTest` : guide mentionne `cloturer_chantier`, mots EXACTS, jamais d'initiative ; définition exige `chantier` et `user_words`.
### Intégration
- `SubjectJourneyChantiersIntegrationTest.closeChantierTool` : Exécution → refus ; Vérification toutes vérifiées → refus sans citation / citation inventée / headless ; OK → Clos, journal `écrit « … »`, puis plus rien à clore → erreur.
- `AtelierChatServiceJourneyTest` existant : système identique (préfixe).
### Isolation
- Applicable : chantier d'un autre terminal inatteignable (le terminal vient du tour) ; attente d'un autre poste introuvable (test existant sur resolveForAgent + nouveau chemin).

## Dépendances
- F-222 / SF-222-04 (mécanisme de citation) — done. Aucune question ouverte impactée.

## Notes et décisions
- La clôture écrite exige Vérification + toutes vérifiées (≙ « clôture proposée »), plus strict que la route du bouton qui clôt en toute phase : un écrit ne doit pas clore un chantier inachevé.
