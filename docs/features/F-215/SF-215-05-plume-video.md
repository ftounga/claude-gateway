# Mini-spec — F-215 / SF-215-05 Plume vidéo : le cahier de réalisation

## Identifiant
`F-215 / SF-215-05` — Feature parente : F-215 « Le cours vidéo libre (court et long) » — Statut : `done` — 2026-10-25 (PR #1283, #1284)
Branches : `feat/SF-215-05-plume-video` (backend), `feat/SF-215-05-plume-video-ecran` (frontend). Extension de F-193.

## Objectif
Quand la demande vise une vidéo, « ✨ Enrichir » produit un **cahier de réalisation** (public, objectif pédagogique,
format unique ou série et durée, chapitres proposés depuis les sources réellement disponibles du sujet, notions à
l'écran, sources, charte, voix, « à confirmer » surlignés) ; pour toute autre demande, le comportement F-193 est inchangé.

## Comportement attendu
### Cas nominal
1. `POST /api/workspaces/{id}/enrich-prompt` (contrat F-193 inchangé, champ ajouté) : la phrase est classée **vidéo** si
   elle parle de vidéo / video, cours ou tutoriel animé, voix off, motion design, screencast ou série d'épisodes
   (`PlumeVideoIntent`, déterministe, insensible à la casse et aux accents). Sinon : chemin F-193 à l'identique.
2. **Matière vidéo** (en plus de la matière F-193, bornée, filtrée `user_id`) — `PlumeVideoContext` :
   - **pages publiées** du sujet puis du poste (titre + description, 20 au plus, les plus récentes) ;
   - **séries vidéo** déjà publiées dans le sujet (titre, nombre d'épisodes, voix fixée) ;
   - la **voix par défaut** de l'utilisateur (préférence SF-215-01, sinon « onyx » + consignes D2) ;
   - la charte par défaut (D1) et la règle D3 sont dans la **consigne** (fixe), pas dans la matière.
3. **Consigne vidéo** : texte **fixe** (préfixe stable, aucune donnée volatile), distinct de la consigne F-193, qui
   impose le Markdown en sections, dans l'ordre :
   `## Public et niveau` · `## Objectif pédagogique` · `## Format et durée` (vidéo unique 1–3 min, ou série de 5 à 15
   épisodes de 1–3 min au-delà de 3 min ou 4 notions — D3) · `## Chapitres proposés` (chacun : ce qu'on y apprend +
   sources **réellement disponibles** dans la matière ; aucune source inventée) · `## Notions à afficher à l'écran` ·
   `## Sources à utiliser` · `## Charte et voix` (charte de l'application par défaut ; voix par défaut de l'utilisateur)
   · `## Vérification attendue` (relecture du guide, fiche des sources) · `## Points à confirmer`.
   Tout ce qui est supposé est suffixé « (à confirmer) » et repris dans « Points à confirmer ».
4. Réponse : `enrichedPrompt`, `toConfirm`, `sources` (« Pages publiées », « Séries vidéo du sujet », « Voix par défaut »
   s'ajoutent quand lues) et **`mode`** : `"video"` ou `"prompt"`.
5. **Écran** : panneau Plume inchangé, sauf le badge « **cahier de réalisation** » (au lieu de « enrichi par Plume ») et
   l'étape « Pages et vidéos du sujet » pendant l'analyse ; « à confirmer » surlignés, « Revenir à mon texte », rien
   n'est envoyé sans geste (F-193).

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| sujet d'un autre compte | introuvable, aucun appel | 404 |
| coupe-circuit fermé | inchangé F-193 | 503 |
| sortie vide / tronquée deux fois | inchangé F-193 | 502 |
| pages / séries / préférence illisibles | matière vidéo partielle, l'enrichissement continue | 200 |
| phrase non vidéo | chemin F-193 inchangé, `mode: "prompt"` | 200 |

## Critères d'acceptation
- [x] CA1 — Une phrase vidéo emprunte la consigne vidéo (sections du cahier) ; une autre phrase garde la consigne F-193.
- [x] CA2 — La matière vidéo liste pages publiées (sujet puis poste), séries du sujet et voix par défaut ; bornée.
- [x] CA3 — La consigne vidéo est fixe (identique d'un appel à l'autre) ; aucune donnée de l'utilisateur dedans.
- [x] CA4 — `mode` vaut `video` / `prompt` ; `toConfirm` lit « Points à confirmer ».
- [x] CA5 — Isolation : pages et séries lues filtrées `user_id` ; sujet d'un autre compte ⇒ 404 sans appel.
- [x] CA6 — Écran : badge « cahier de réalisation » en mode vidéo, « enrichi par Plume » sinon ; rien envoyé sans geste.

## Contraintes de validation
| Élément | Borne |
|---|---|
| pages lues | 20 au plus (titre 120, description 300) |
| séries lues | 20 au plus |
| matière vidéo | 3 000 caractères au plus |
| consignes de voix rappelées | 300 caractères au plus |

## Technique
- Backend : `atelier/plume/PlumeVideoIntent` (classement), `PlumeVideoContext` (matière vidéo), `PlumeService`
  (`CONSIGNE_VIDEO`, aiguillage), `EnrichPromptResponse` (+ `mode`, constructeur historique conservé — record),
  `PageRepository.findTop20ByUserIdAndWorkspaceIdOrderByUpdatedAtDesc`.
- Frontend : `plume.service.ts` (`mode?`), `terminal-plume.component.ts` (badge, étape), appelant du panneau.
- Aucune table, aucune migration, aucune route nouvelle.

## Plan de test
- Unitaires : `PlumeVideoIntentTest` (vidéo / non-vidéo, accents, casse) ; `PlumeServiceTest` (consigne vidéo choisie,
  matière vidéo présente, consigne identique entre deux appels, phrase non vidéo ⇒ F-193 + `mode: prompt`) ;
  `PlumeVideoContextTest` (pages sujet puis poste dédupliquées, séries, voix par défaut / préférence, bornes, erreurs
  avalées).
- Intégration : `PlumeApiIntegrationTest` (mode vidéo bout en bout avec fournisseur simulé ; 404 d'un autre compte).
- Front : spec du panneau (badge selon le mode) + spec existante Plume verte.

## Préoccupations transversales
- **Contexte tenant** : `PlumeVideoContext` lit pages, séries et préférence par `user_id` (et sujet / poste du
  terminal possédé). Composants : `PlumeService`, `PlumeVideoContext`, `PageRepository`, `VideoSeriesRepository`,
  `SpeechPreferenceService`.
- **Plans / limites** : usage compté comme F-193 (journal d'usage), aucun nouveau poste de coût.
- Auth / navigation : non.
- **Préfixe stable** : deux consignes fixes ; la matière (volatile) va dans le message.

## Hors périmètre
Lancer la fabrication ; choix de voix par auditions (SF-215-01) ; lecture du contenu des pages (titres et descriptions
seulement) ; classement par IA de l'intention.

## Notes et décisions (arbitrages réversibles)
- **A1** — Classement **déterministe** par mots-clés (gratuit, prévisible, testable) plutôt qu'un appel IA.
- **A2** — Pages du sujet **puis** du poste : l'essai EKS partait d'une page publiée ailleurs sur le poste.
- **A3** — Champ `mode` ajouté à la réponse (rétrocompatible) pour le libellé du badge.
