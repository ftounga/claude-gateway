# Mini-spec — F-231 / SF-231-05 « Jarvis… »

## Identifiant
`F-231 / SF-231-05` — Feature parente : F-231 « Le mode Jarvis » (cadrage, décisions **D5, D7, D9**).
Statut : `ready` — 2026-11-01 — Branche `feat/SF-231-05-jarvis-mot-d-eveil`. Dépend de SF-231-02 et du contrat d'alerte F-230 (`GET /api/jarvis/alerts/{id}`), mergés.

## Objectif
Réveiller Jarvis en disant « Jarvis » sans qu'aucun son ne quitte l'appareil, le lancer par « Ok Google, ouvre Jarvis », lui faire lire les alertes de veille, et mesurer son adoption.

## Comportement attendu

### Cas nominal
1. **Mot d'éveil sur l'appareil (D5)** — moteur `WakeWordEngine` (interface remplaçable), première implémentation **par gabarits appris** : « Apprendre « Jarvis » » fait dire le mot trois fois ; l'empreinte (MFCC, 12 coefficients / 10 ms) reste dans le `localStorage` de l'appareil ; ensuite chaque mot isolé (0,2 à 1,5 s, suivi de 0,25 s de calme) est comparé aux gabarits par alignement temporel (DTW) sous un seuil appris (écart maximal entre gabarits × 1,35). **Rien n'est envoyé, rien n'est enregistré, coût 0 €.** Actif tant que l'écran Jarvis est ouvert et le réglage allumé ; il se tait pendant la dictée et reprend ensuite. « Jarvis » entendu ⇒ Jarvis coupe sa voix s'il parlait, et écoute.
2. **« Ok Google, ouvre Jarvis »** — l'écran `/jarvis` porte son **propre manifeste** (`jarvis.webmanifest` : `name`/`short_name` « Jarvis », `id`/`start_url` `/jarvis`, `scope` `/jarvis`) : installé depuis cet écran, il devient une application Android nommée Jarvis, que l'assistant ouvre directement en mode Jarvis. Le manifeste principal gagne un **raccourci** « Jarvis » (appui long sur l'icône). Le manifeste d'origine est rétabli en quittant l'écran.
3. **Alertes de veille lues (D7)** — `app.jarvis.route-enabled` passe à **`true`** par défaut : le lien d'une alerte F-230 devient `/jarvis?alerte=<id>`. L'écran lit l'alerte (`GET /api/jarvis/alerts/{id}`), passe sur son sujet, la dit (« Veille X : <phrase>. Que veux-tu faire ? »), la marque ouverte (`POST …/open`) et **écoute la réponse**. Si le navigateur refuse le son avant le premier geste : « Touche l'orbe pour écouter l'alerte. » et elle est dite au premier appui.
4. **Mesure d'adoption (D9)** — l'écran déclare `POST /api/jarvis/usage` `{event: SESSION | SPOKEN (+seconds) | WAKE}` ; cumul par jour dans `jarvis_usage_days` (SF-231-01). `GET /api/admin/jarvis/measure?days=7` (ADMIN, sur **ses** journées) → `{days, activeDays, sessions, spokenMinutes, wakes, charsSpoken, voiceCostEur, perDay[]}` — coût de la voix **en euros**. Elle conditionne F-232.

### Contrat API
```json
// POST /api/jarvis/usage
{ "event": "SPOKEN", "seconds": 12 }            // → 204
// GET /api/admin/jarvis/measure?days=7
{ "days": 7, "activeDays": 3, "sessions": 9, "spokenMinutes": 14.5, "wakes": 21, "charsSpoken": 4210,
  "voiceCostEur": 0.06, "perDay": [ { "day": "2026-11-01", "sessions": 2, "spokenMinutes": 3.0, "wakes": 5,
  "charsSpoken": 900, "voiceCostEur": 0.01 } ] }
```

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Événement inconnu | `validation_error` | 400 |
| Secondes négatives / > 600 | ramenées à 0–600 | 204 |
| Mesure par un non-ADMIN | refus | 403 |
| Sans jeton | refus | 401 |
| Micro refusé (mot d'éveil, apprentissage) | message ; mot d'éveil éteint | — |
| Alerte introuvable (autre compte) | « Cette alerte est introuvable. » (404 côté passerelle, F-230) | — |

## Contraintes de validation
3 répétitions à l'apprentissage ; mot isolé 20 à 150 trames ; fenêtre de mesure 1 à 90 jours (défaut 7).

## Critères d'acceptation
- [ ] Un mot proche des gabarits est accepté, un autre refusé ; un mot trop bref ou une phrase sont ignorés.
- [ ] Aucun appel réseau par le mot d'éveil ; il se tait pendant la dictée.
- [ ] `/jarvis` sert `jarvis.webmanifest` (nom « Jarvis », `start_url /jarvis`) ; le manifeste principal a le raccourci.
- [ ] `/jarvis?alerte=` lit l'alerte sur son sujet puis écoute ; le lien d'alerte mène à `/jarvis`.
- [ ] La mesure compte sessions, minutes parlées, réveils, coût en € ; isolée par compte ; 403 non-ADMIN.

## Plan de test
- **Unitaires (front)** : `jarvis-wake.spec` (rééchantillonnage, empreinte, DTW, acceptation/refus, découpe des mots).
- **Composant** : `jarvis.component.spec` (session comptée, alerte lue sur son sujet puis écoute, son refusé ⇒ au premier appui).
- **Intégration (back)** : `JarvisAdoptionApiIntegrationTest` (comptage, bornes, **isolation**, 400/403/401), `JarvisAlertApiIntegrationTest` (lien `/jarvis?alerte=`).

## Tables / endpoints / composants impactés
- Table `jarvis_usage_days` (SF-231-01, colonnes `sessions`, `spoken_seconds`, `wakes`) — pas de migration nouvelle.
- Endpoints : `POST /api/jarvis/usage`, `GET /api/admin/jarvis/measure`. Configuration `app.jarvis.route-enabled` (défaut `true`).
- Front : `jarvis-wake` (moteur), `JarvisComponent`, `JarvisService.usage`, `public/jarvis.webmanifest`, `public/manifest.webmanifest` (raccourci), `ngsw-config.json` (manifeste mis en cache).

## Préoccupations transversales
- **Navigation / routing** ✅ — lien profond des notifications F-153 : `JarvisAlertLinks` rend `/jarvis?alerte=` (vérifié par test) ; la route `/jarvis` est déjà gardée (SF-231-02) ; raccourci PWA vers `/jarvis` ; le manifeste est rétabli en quittant l'écran (aucun effet sur `/atelier`).
- **Plans / limites** ✅ — composants : `JarvisUsageService` (cumul), `JarvisVoiceService` (plafond inchangé) ; le mot d'éveil ne coûte rien.
- **Contexte tenant** ✅ — `JarvisAdoptionService` écrit et lit sous le `user_id` du jeton ; l'alerte est lue par la route F-230 (404 chez autrui).

## Confidentialité
Le mot d'éveil n'envoie rien : gabarits et analyse restent sur l'appareil. Seule la dictée qui suit le réveil part chez le fournisseur de transcription (régime F-145, inchangé).

## Hors périmètre
Écoute téléphone verrouillé (F-232). Un moteur neuronal du mot d'éveil (remplaçable derrière `WakeWordEngine`). iPhone (pas de lancement par l'assistant).
