# Mini-spec — F-216 / SF-216-01 Le devis d'une vidéo, calculé par le serveur

## Identifiant
`F-216 / SF-216-01` — Feature parente : F-216 « Le coût d'une vidéo, annoncé avant et mesuré après » — Statut : `in-progress` — 2026-10-25
Branche : `feat/SF-216-01-devis`

## Objectif
Avant toute fabrication d'une vidéo, d'une série ou d'une retouche, l'agent obtient du serveur un **devis** (fourchette
basse–haute ventilée voix / agent / rendu) et le place dans la **question unique** de validation.

## Comportement attendu
### Cas nominal
1. **Outil `video_devis`** (texte fixe, préfixe stable), donné avec `video_publier` (même garde : droit de l'espace du
   terminal + cible poste), exécuté **par la gateway**, **sans accord** (gratuit, n'écrit rien sur le poste), permis
   en mode « répondre / planifier ». Paramètres : `episodes` (défaut 1), `duree_min` (d'un épisode), `caracteres`
   (narration d'un épisode, défaut = durée × `chars-per-minute`), `retouches` (défaut 1), `serie` (titre), `video`
   (video_id ⇒ devis de **retouche**).
2. **Genre** : `video` ⇒ `RETOUCHE` ; `episodes > 1` ou `serie` ⇒ `SERIES` ; sinon `SINGLE`.
3. **Calcul** (`VideoQuoteCalculator`, pur) — N épisodes de d minutes, c caractères, r retouches, t = coût d'un tour :
   - voix d'un épisode `v = c × tarif_1000/1000 + d × tarif_min` (**tarifs de `app.speech.*`**, ceux de `voix_off`) ;
     voix basse = N·v, haute = N·v·(1 + r·`retouche-voice-fraction`) ;
   - tours bas = N·(d·`turns-per-minute-low` + r·`turns-per-retouche-low`) (+ `series-planning-turns-low` en série),
     hauts idem avec les coefficients `-high` ; agent = tours × t ;
   - retouche : voix basse 0, haute = r·fraction·v ; tours = r·`turns-per-retouche-low/high` ;
   - total = voix + agent ; bas arrondi au cent inférieur, haut au cent supérieur ; rendu **0 $** ; temps de rendu =
     ⌈N·d·`render-minutes-per-video-minute`⌉ min.
4. **Coût d'un tour** (`VideoTurnCostReader`) : moyenne **du compte** des tours de la boucle sur `turn-cost-window`
   (7 j) dans `atelier_turn_measures` (filtre `user_id`, modèle = `app.atelier.model`), chiffrée par
   `ProviderCostCalculator` ; moins de `min-turn-samples` (5) tours ou coût nul ⇒ `default-turn-cost-usd` (1,00 $).
5. **Devis rangé** (`video_quotes`, statut `OPEN`) : un nouveau devis vidéo/série clôt (`CLOSED`) les devis ouverts du
   même sujet ; un devis de retouche ne clôt que les autres retouches ouvertes du sujet.
6. **Résultat d'outil** : identifiant du devis, ce qui est chiffré, la ligne prête à copier
   « Coût estimé : 4,80 à 7,20 $ (voix 0,40 $ · agent 4,40 à 6,80 $ · rendu 0 $ — votre poste) », le détail
   (caractères, tours × coût d'un tour, source « mesuré sur N tours » ou « valeur par défaut »), durée totale, temps de
   rendu, puis la consigne : vidéo/série ⇒ « place la ligne dans la question unique (demander) » ; retouche sous le
   seuil `retouche-confirm-threshold-usd` (3 $) ⇒ « annonce-le, sans question » ; au-dessus ⇒ « fais-le confirmer
   par demander ».
7. **Guide `cours-video.md`** (chargé à la demande) : devis **obligatoire avant toute fabrication, vidéo unique
   comprise** — fiche (titre, objectif, durée, voix, sources) + ligne du devis dans **une** question `demander` ; § 7.1
   (série) : le repère écrit est remplacé par `video_devis` ; § 6.4 (retours) : devis de retouche annoncé, question
   au-delà du seuil. Ne jamais estimer soi-même.

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| `duree_min` absente sans `video` | erreur d'outil « donne duree_min » | — |
| `duree_min` ∉ [0,25 ; 30], `episodes` ∉ [1 ; 30], `retouches` ∉ [0 ; 10], `caracteres` ∉ [50 ; 50 000] | erreur d'outil explicite | — |
| `video` mal formé / d'un autre compte / inconnu | erreur d'outil « vidéo introuvable » | — |
| `video` sans durée connue ni `duree_min` | erreur d'outil « donne duree_min » | — |
| outil hors garde (pas de poste, pas de droit) | non déclaré ; appel forcé ⇒ erreur, rien n'est rangé | — |
| mesures illisibles (panne SQL) | valeur par défaut, le devis est rendu | — |

## Critères d'acceptation
- [ ] CA1 — `video_devis` est déclaré avec `video_publier` (même garde), exécuté par la gateway sans accord, jamais relayé au poste, permis en mode planifier.
- [ ] CA2 — Le calcul suit la formule (voix aux tarifs `app.speech.*`, tours × coût d'un tour, rendu 0 $, arrondis bas/haut), unitaires sur des valeurs connues (série, vidéo unique, retouche).
- [ ] CA3 — Le coût d'un tour est la moyenne mesurée du compte sur 7 j (≥ 5 tours, modèle courant), sinon le défaut ; un autre compte n'influe pas.
- [ ] CA4 — Le devis est rangé `OPEN` ; un nouveau devis vidéo/série clôt les ouverts du sujet ; une retouche ne clôt que les retouches.
- [ ] CA5 — Le résultat porte la ligne « Coût estimé : … (voix … · agent … · rendu 0 $ — votre poste) », la durée, le temps de rendu et la consigne (question unique / seuil de retouche).
- [ ] CA6 — Retouche d'une vidéo d'un autre compte ⇒ « vidéo introuvable » (isolation).
- [ ] CA7 — Le guide exige le devis avant toute fabrication (vidéo unique comprise), la question unique, le seuil de retouche ; la consigne système n'en contient rien.

## Contraintes de validation
| Champ | Obligatoire | Format |
|---|---|---|
| `episodes` | Non (1) | entier 1–30 |
| `duree_min` | Oui sauf `video` | nombre 0,25–30 |
| `caracteres` | Non | entier 50–50 000 (par épisode) |
| `retouches` | Non (1) | entier 0–10 |
| `serie` | Non | 120 caractères au plus |
| `video` | Non | UUID d'une vidéo du compte |

## Technique
- Migration **173** `173-video-quotes.xml` : table `video_quotes` (id, user_id FK cascade, workspace_id FK cascade,
  kind, series_title, video_id sans FK, episodes, episode_duration_s, narration_chars, retouches, voice/agent/total
  low/high `numeric(10,4)`, turn_cost_usd, turn_cost_measured, render_minutes, status, created_at, closed_at), index
  `(user_id, workspace_id, status)` ; rollback : table retirée.
- Backend (`fr.claudegateway.videos`) : `VideoCostProperties` (`app.videos.cost.*`, un seul constructeur canonique),
  `VideoQuoteCalculator`, `VideoTurnCostReader`, `VideoQuote` + `VideoQuoteRepository`, `VideoQuoteService` (calcul,
  rangement, texte), `VideoToolCatalog` (+ `video_devis`) ; `AtelierChatService` : routage `applyVideoDevis`,
  `ANSWER_PLAN_TOOLS`, garde du chemin machine ; `application.yml` (coefficients documentés) ; guide `cours-video.md`.

## Plan de test
- Unitaires : `VideoQuoteCalculatorTest` (série 6 × 2,5 min, vidéo unique, retouche, arrondis, caractères par défaut,
  défauts et valeurs invalides de `VideoCostProperties`, durées). Le texte rendu (ligne, consignes, seuil) est vérifié
  en intégration.
- Intégration : `VideoQuoteIntegrationTest` (H2 : coût d'un tour mesuré ≥ 5 tours ⇒ moyenne ; < 5 ⇒ défaut ; mesures
  d'un **autre compte** ignorées ; devis rangé `OPEN`, supersession vidéo/série vs retouche ; retouche d'une vidéo d'un
  autre compte ⇒ introuvable) ; `AtelierChatServiceVideoDevisTest` (déclaré avec la garde, exécuté sans accord ni
  relais) ; `GovernancePackageSeederTest` (guide : devis obligatoire, vidéo unique, seuil).
- Suite complète backend.

## Préoccupations transversales
- **Contexte tenant** : devis rangé et lu par `user_id` + sujet du tour (déjà vérifié possédé) ; mesures filtrées par
  `user_id` ; vidéo de retouche lue par `VideoService.require(userId, id)`. Composants : `VideoQuoteService`,
  `VideoTurnCostReader`, boucle `AtelierChatService`.
- **Plans / limites** : aucun gate, aucun usage payant (lecture des mesures seulement).
- Auth / navigation : non. **Préfixe stable** : définition d'outil fixe, rien dans la consigne système.

## Hors périmètre
Imputation du coût réel et affichage (SF-216-02/03) ; blocage dur sans devis ; auto-étalonnage des coefficients.

## Notes et décisions
- **A1** — Coût d'un tour lu dans `atelier_turn_measures` (seulement les tours de la boucle) plutôt que `usage_turns`
  (qui mêle la voix et Plume, au même modèle pour Plume) ; chiffré par la grille existante — même montant que
  `usage_turns` pour ces tours.
- **A2** — Moyenne **par compte** (isolation, représentative de l'usage de l'utilisateur), défaut sinon.
- **A3** — Le devis n'exige pas d'accord : c'est un calcul ; l'accord est la question de validation du plan.
