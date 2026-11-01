# Mini-spec — F-233 / SF-233-07 — Audition à l'aveugle et bascule (D7, D-PO-6)

## Identifiant
`F-233 / SF-233-07` · Feature parente `F-233` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-233-07-audition`

## Objectif
> L'administrateur écoute les mêmes phrases françaises dites par des voix **anonymisées** (A, B, C…), choisit, et la
> voix choisie devient **sa** voix de Jarvis — avant l'arrêt de `gpt-4o-mini-tts` le 06/01/2027.

## Comportement attendu
- **Candidats** (configuration `app.jarvis.audition.*`, imposés par le PO le 2026-11-01) : ElevenLabs **Daniel**
  (`onwK4e9ZLuTAKqWW03F9`) et **George** (`JBFqnCBsd6RMkjVDRZzb`), la voix **décrite** (Voice Design : « homme posé,
  élégance britannique, parle français ») si son identifiant est fourni (`APP_JARVIS_AUDITION_DESIGNED_VOICE_ID`), et
  **Onyx** (OpenAI) en référence. Aucun clonage de voix réelle.
- **À l'aveugle** : l'ordre des candidats est mélangé de façon stable par compte et par nombre de choix déjà faits ;
  la liste ne rend que `label` (A, B, C…) et `available`. Rien ne révèle fournisseur ni voix avant le choix.
- **Phrases** : liste fixe de phrases françaises (sans donnée client), les mêmes pour tous les candidats ; écoute
  répétable.
- **Choix** : enregistre `(user_id, provider, voice, label)` ; la voix devient celle de Jarvis **pour ce compte**
  (lecture et clips — les clips se régénèrent d'eux-mêmes, clé = voix) ; la réponse **révèle** alors la voix choisie.
  « Revenir à la voix par défaut » efface le choix.
- Écran : section « Voix de Jarvis — audition à l'aveugle » dans la page Administration (garde ADMIN existante).

## Contrat API (ADMIN, `assertAdmin`, sur ses propres données)
| Route | Rend |
|---|---|
| `GET /api/admin/jarvis/audition` | `{ phrases: string[], candidates: [{ label, available }], chosen: { label?, provider, voice, name } \| null }` |
| `POST /api/admin/jarvis/audition/{label}/sample` `{ phrase }` | `audio/mpeg` (index de phrase) |
| `POST /api/admin/jarvis/audition/{label}/choose` | `{ label, provider, voice, name }` (révélation) |
| `DELETE /api/admin/jarvis/audition/choice` | `204` (retour à la voix par défaut) |

## Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Libellé inconnu / index de phrase hors liste | `not_found` / `validation_error` | 404 / 400 |
| Candidat indisponible (pas de clé ElevenLabs) | `speech_unavailable` (échantillon et choix refusés) | 503 |
| Plafond voix atteint | `jarvis_voice_budget` (échantillon) | 429 |
| Fournisseur en échec | `speech_failed` | 502 |
| Non-ADMIN | refus | 403 |

## Critères d'acceptation
1. La liste n'expose ni fournisseur ni voix ; l'ordre est stable pour un compte tant qu'il ne choisit pas.
2. L'échantillon d'un candidat est dit par sa voix, compté au tarif de son fournisseur.
3. Après le choix, `GET /api/jarvis/voice` et la lecture de ce compte utilisent la voix choisie ; un autre compte
   garde la voix par défaut.
4. Revenir à la voix par défaut rétablit la configuration.

## Plan de test
- Unitaires : mélange stable et dépendant du compte ; candidats (voix décrite absente si non configurée).
- Intégration : liste aveugle, échantillon (fournisseur simulé), choix ⇒ voix de Jarvis du compte (et pas d'un autre),
  retour au défaut, 404/400/503/403.
- Écran : liste A/B/C, écoute, choix révélé, retour au défaut (service simulé).

## Impacts
- **Table** `jarvis_voice_choices` (migration `198-…`) : `id, user_id, provider, voice, label, chosen_at`.
- `JarvisVoiceService.current(userId)` (choix du compte, sinon configuration), `JarvisClipService` (clé par voix du
  compte), `JarvisAuditionService` / `JarvisAuditionController` / `JarvisAuditionProperties`.
- Écran : `AdminJarvisVoiceComponent` dans `AdminComponent`.

## Préoccupations transversales
- Plans / limites : oui — `JarvisVoiceService.status/prepare/synthesizeClip/costUsd` lisent la voix du compte ;
  l'échantillon passe par le plafond.
- Contexte tenant : le choix est rangé et lu sous `user_id`.
- Navigation : aucune route nouvelle (section de `/admin`).

## Arbitrages (réversibles)
- La voix choisie vaut **pour le compte qui choisit** (isolation `user_id`), pas pour toute la passerelle.
- La voix « décrite » se crée chez ElevenLabs (Voice Design) au moment de l'activation : son identifiant est un
  réglage, faute de clé ici ; sans lui, trois candidats.
- Proposer la voix gagnante à la voix off F-215 : affiché comme suggestion, aucune bascule automatique de F-215.

## Hors périmètre
Création automatique de la voix décrite ; bascule de la voix off.
