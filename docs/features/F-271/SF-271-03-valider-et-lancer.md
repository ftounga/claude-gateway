# Mini-spec — [F-271 / SF-271-03] Valider et lancer en un « oui »

## Identifiant
`F-271 / SF-271-03`

## Feature parente
`F-271` — Jarvis lance le travail (cadrage `CADRAGE-F-271-jarvis-lance-le-travail.md`, §5 Engagements, S4, S7, S8)

## Statut
`in-progress`

## Date de création
2026-11-21

## Branche Git
`feat/SF-271-03-valider-et-lancer`

---

## Objectif
« Valide et lance LDIC-325 », puis « oui » : le plan est validé et la course autonome programmée sur ce seul « oui »
dit, journalisé avec sa transcription ; aucun départ à l'oral sans ce « oui » ; un refus est dit ; une question de
lancement restée sans réponse est close à voix haute.

## Comportement attendu

### Cas nominal
1. Le tour sait qu'il est né à la voix (`Workspace.turnViaJarvis`, transitoire, posé avec la marque vocale).
2. **Canal journalisé** : dans un tour né à la voix, les gestes cités (`valider_plan`, `cloturer_chantier`,
   `geste_hors_chantier`, ouverture SF-271-01) sont rangés `dit « … »` (la transcription) au lieu de `écrit « … »`.
3. `lancer_chantier_autonome` reçoit `user_words` (facultatif au terminal). **Tour né à la voix** : `user_words` exigé et
   cité dans ce tour (le « oui » transcrit), sinon refus « reformule et demande « Vous confirmez ? » », rien n'est
   programmé. Tour sans utilisateur : refus.
4. Chaque départ programmé par l'outil est journalisé : événement `AUTONOMY_REQUESTED`
   `chantier N · STRICT|UNTIL_BLOCKED · dit « oui »` (ou `écrit « … »`, ou `outil` sans citation au terminal).
5. **Enchaînement** : le résultat de `valider_plan` dit, si l'utilisateur a demandé « valide et lance », d'appeler
   `lancer_chantier_autonome` dans le même tour, sans exécuter les étapes ; à l'oral, le départ programmé finit sur
   « Ta ligne 🎙 : « C'est parti. » ».
6. **Refus dit** : un départ refusé (préflight rouge, limite de 4, déjà en course, poste éteint) rend ses raisons
   (inchangé) + à l'oral « dis en une phrase ce qui manque (« il manque… »), sans relancer ».
7. **Écran** : une confirmation armée dont la question parle de lancer (« … et le lance en autonome. Vous confirmez ? »)
   et restée sans réponse 2 min est **close à voix haute** à la pause : « Sans réponse, je n'ai pas lancé LDIC-325. ».
   Le nom d'une confirmation est la clé de ticket du libellé **ou de la phrase dite**.
8. **Arrêt nommé** (« Arrête LDIC-330 ») : déjà livré par F-270 / SF-270-04 (course ⇒ reprendre la main) — inchangé.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Voix, `user_words` absent | erreur d'outil, rien programmé |
| Voix, citation non dite dans ce tour | erreur d'outil, rien programmé |
| Préflight rouge / limite / poste éteint | erreur avec raisons ; à l'oral « il manque… » |
| Confirmation non lancement expirée | silencieuse (inchangé) |
| Terminal (tapé) sans `user_words` | inchangé : programmé, journal `outil` |

## Critères d'acceptation
- [ ] Voix : sans « oui » cité ⇒ rien programmé ; « oui » cité ⇒ programmé, journal `dit « oui »`.
- [ ] Terminal : comportement inchangé, journal `outil`.
- [ ] `valider_plan` à la voix ⇒ journal `PLAN_VALIDATED … dit « … »`.
- [ ] Confirmation de lancement expirée ⇒ « Sans réponse, je n'ai pas lancé X. » dit une fois.
- [ ] Nom d'une confirmation : clé de la phrase si le libellé n'en a pas.
- [ ] Banc à vraie voix 2 passages : pas de régression.

## Périmètre

### Hors scope (explicite)
- La course qui parle (fin, blocage) : SF-271-04. L'écran (constellation, halo) : SF-271-05.
- Liste d'outils du cerveau rapide : inchangée.

## Valeurs initiales
| Paramètre | Valeur |
|---|---|
| Expiration d'une confirmation | 2 min (`CONFIRM_TTL_MS`, F-270) |
| Détection « lancement » | la phrase dite contient « lanc » |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `user_words` (lancer) | cité mot pour mot dans le tour (règle `quoted`) ; exigé à la voix |
| Détail du journal | ≤ 300 caractères (colonne existante), citation coupée à 120 |

## Technique

### Endpoint(s)
Aucun.

### Tables impactées
`subject_journey_events` (nouveau type `AUTONOMY_REQUESTED`, colonne `type` varchar(32) existante).

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
- `jarvis/jarvis-works.ts` : `ArmedConfirmation.launch`, `takeExpired()`.
- `jarvis/jarvis.component.ts` : annonce de l'expiration, nom tiré de la phrase.

### Composants backend
`Workspace` (transitoire), `AtelierChatService` (pose), `JourneyToolExecutor` (canal, départ oral), `JourneyToolCatalog`
(`user_words` de `lancer_chantier_autonome`), `SubjectJourneyService.recordAutonomyRequested`, `SubjectJourneyEvent`.

## Plan de test

### Tests unitaires
- `jarvis-works.spec.ts` : `takeExpired` rend une fois les confirmations de lancement expirées.
- `jarvis.component.spec.ts` : expiration annoncée ; nom tiré de la phrase.

### Tests d'intégration
- `AutonomyApiIntegrationTest` : départ oral sans / avec citation, journal ; terminal inchangé.
- `SubjectJourneyChantiersIntegrationTest` : canal `dit « … »` de `valider_plan` à la voix.

### Isolation workspace
- Les outils n'agissent que sur le terminal du tour (`user_id` du tour) — inchangé ; testé par les classes existantes.

## Dépendances

### Subfeatures bloquantes
SF-271-01, SF-271-02.

### Questions ouvertes impactées
Aucune.

## Préoccupations transversales
Aucune (pas d'auth, de tenant, de limite ni de navigation modifiés ; la limite de 4 courses est lue, inchangée).

## Notes et décisions
- **D1** « Vas-y » oral garanti côté passerelle (pas seulement par la consigne) : la règle « 0 action lancée sans oui
  journalisé » (cadrage §9) devient vérifiable dans `subject_journey_events`.
- **D2** `lancer_chantier_autonome` gagne un paramètre : la liste d'outils d'Opus change une fois (déjà le cas en
  SF-271-01, même déploiement).
