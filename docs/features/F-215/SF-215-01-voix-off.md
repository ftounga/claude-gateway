# Mini-spec — F-215 / SF-215-01 Voix off : `SpeechProvider`, outil `voix_off`, auditions, voix par défaut

## Identifiant
`F-215 / SF-215-01` — Feature parente : F-215 « Le cours vidéo libre (court et long) » — Statut : `in-review` — 2026-10-24
Branches : `feat/SF-215-01-voix-off` (backend, PR 1) puis `feat/SF-215-01-voix-off-ecran` (Paramètres › Vidéos, PR 2).

## Objectif
L'agent produit la **voix off** d'une vidéo par un outil serveur `voix_off` : le texte est lu par un fournisseur de synthèse vocale derrière l'interface `SpeechProvider` (OpenAI `gpt-4o-mini-tts`), le mp3 est déposé dans le dossier du sujet sur le poste **avec l'horodatage de chaque mot** (fournisseur de transcription existant, mode mots), dans la voix choisie par l'utilisateur ; il peut aussi faire écouter 3–4 voix (auditions).

## Comportement attendu
### Cas nominal
- **Outil `voix_off`** (cible poste, même garde d'espace que `video_publier` ; déclaré seulement si la voix est allumée et configurée ; accord d'un clic, « autoriser pour tout le tour » valable) :
  - `texte` (requis, ≤ 4 000 caractères), `voix?` (liste blanche des voix du fournisseur), `consignes?` (ton, ≤ 1 000 caractères), `fichier?` (chemin relatif du mp3 dans le projet, nettoyé ; défaut `voix/voix-off-<8 hex>.mp3`), `auditions?` (booléen), `voix_auditions?` (2 à 4 voix).
  - **Voix effective** : `voix` de l'appel > préférence du compte > **« onyx »** (D2). **Consignes effectives** : `consignes` de l'appel > préférence du compte > « formateur technique français, voix posée, claire et chaleureuse, rythme pédagogique » (D2).
  - Le mp3 est déposé dans le projet (poste) ; l'audio est ensuite transcrit **en mode mots** (`timestamp_granularities[]=word`, modèle Whisper) ; un fichier `<nom>.mots.json` (`{voix, duree_s, mots:[{mot, debut, fin}]}`) est déposé à côté ; le résultat d'outil rend chemin, voix, durée et la liste des mots horodatés (`début–fin mot`, 800 mots au plus en ligne ; au-delà, renvoi au fichier).
  - **Auditions** (`auditions: true`) : la même phrase (≤ 300 caractères) lue par 3–4 voix (défaut `onyx`, `ash`, `nova`, `coral`), déposées sous `voix/auditions/<voix>.mp3` puis **publiées par `audio_publier`** (F-213, bloc audio du fil, lecteurs empilés « Voix n — nom ») ; le résultat invite à proposer le choix par une question structurée (`demander`).
- **Usage** : chaque appel (synthèse + transcription) est inscrit au **journal d'usage** (`UsageLedgerService.recordTurn`, comme Plume) : jetons d'entrée estimés (caractères / 4), coût calculé (0,015 $ / 1 000 caractères + 0,006 $ / minute transcrite, configurables), modèle `gpt-4o-mini-tts`, rattaché à `user_id` + sujet + poste. Aucun contenu journalisé.
- **Coupe-circuit** : `APP_ATELIER_SPEECH_ENABLED=false` ⇒ outil non déclaré, routes en 503 ; clé absente ⇒ idem (« voix non configurée »).
- **Clé** : **aucun nouveau secret** — `app.speech.api-key` vaut `${APP_SPEECH_API_KEY:${APP_STT_API_KEY:}}` (la clé OpenAI de la dictée / STT déjà en prod).
- **Voix par défaut du compte** (amendement PO, points 1, 2, 4) : table `speech_preferences` (une ligne par compte), routes :
  - `GET /speech/voices` → `{enabled, voices:[{id,label}], defaultVoice, defaultInstructions, preference:{voice,instructions}|null}` ;
  - `PUT /speech/preference` `{voice, instructions?}` → 200 (vue) / 400 ;
  - `DELETE /speech/preference` → 204 (retour aux réglages D2) ;
  - `GET /speech/voices/{voice}/sample` → `audio/mpeg`, **échantillon fixe** (même phrase pour toutes les voix, consignes D2), généré une fois par voix puis gardé en mémoire (coût borné à la liste des voix) ; chaque génération est inscrite au journal d'usage.
  - Changer la préférence n'altère aucun fichier ni vidéo existants ; « refais-la avec une autre voix » = nouvel appel `voix_off` + `video_publier(remplace)` (guidé par la description de l'outil).
- **Écran** (PR 2) : carte « Vidéos » des Paramètres — liste des voix, ▶ Écouter (échantillon), consignes de ton modifiables, Enregistrer, Revenir au réglage par défaut ; carte masquée si la voix est éteinte.

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| `texte` absent / > 4 000 car. ; phrase d'audition > 300 car. | résultat d'outil en erreur explicite | — |
| voix hors liste | erreur listant les voix permises | — / 400 |
| consignes > 1 000 car. | erreur | — / 400 |
| voix éteinte (coupe-circuit) ou clé absente | outil non déclaré ; appel égaré ⇒ « voix non configurée » | 503 |
| fournisseur en erreur / délai | erreur d'outil « réessaie », rien déposé, rien compté | 502 côté échantillon |
| dépôt sur le poste en échec (runner ancien, poste hors ligne) | erreur « dépôt échoué » | — |
| transcription indisponible / en échec | mp3 déposé, résultat **non bloquant** « horodatage indisponible » (pas de `.mots.json`) | — |
| plus de 20 appels dans un tour | refus « limite par message » | — |
| refus / délai de l'accord | « refusé », rien produit | — |
| `fichier` avec `..`, chemin absolu, extension autre que .mp3 | nettoyé (segments sûrs, `.mp3` imposé) | — |
| voix inconnue pour l'échantillon | introuvable | 404 |

## Critères d'acceptation
- [ ] `SpeechProvider` (interface) + `OpenAiSpeechProvider` : `POST {base}/audio/speech` `{model, input, voice, instructions, response_format:"mp3"}`, Bearer, aucun appel si non configuré ; ni texte ni clé journalisés.
- [ ] `TranscriptionProvider.transcribeWords` (méthode par défaut) + implémentation HTTP : `verbose_json` + `timestamp_granularities[]=word`, modèle Whisper.
- [ ] `voix_off` dépose le mp3 et `.mots.json` dans le projet et rend les mots horodatés ; voix et consignes résolues appel > préférence > D2.
- [ ] Auditions : 3–4 mp3 déposés puis un bloc `audio` publié via `audio_publier`.
- [ ] Une ligne au journal d'usage par appel (coût > 0, modèle `gpt-4o-mini-tts`) ; rien si le fournisseur échoue.
- [ ] Coupe-circuit : outil non déclaré et routes 503 quand `enabled=false`.
- [ ] Préférence : GET / PUT / DELETE scellés par le `user_id` du jeton ; un compte ne voit jamais la préférence d'un autre ; purge à la suppression du compte.
- [ ] Échantillon : `audio/mpeg`, mis en cache par voix (un seul appel fournisseur pour deux écoutes).
- [ ] Écran Paramètres › Vidéos : choisir, écouter, modifier les consignes, enregistrer, revenir au défaut.

## Contraintes de validation
| Champ | Obligatoire | Max | Format | Normalisation |
|---|---|---|---|---|
| texte | Oui | 4 000 | non vide | strip |
| texte (auditions) | Oui | 300 | non vide | strip |
| voix / voix_auditions | Non | — | liste blanche `app.speech.voices` (alloy, ash, ballad, coral, echo, fable, nova, onyx, sage, shimmer, verse) ; 2–4 voix distinctes | minuscules |
| consignes / instructions | Non | 1 000 | texte libre | strip, vide ⇒ défaut |
| fichier | Non | 200 | segments `[A-Za-z0-9._-]`, `.mp3` imposé | `..` et absolus retirés |

## Technique
- Endpoints : `GET /speech/voices`, `PUT /speech/preference`, `DELETE /speech/preference`, `GET /speech/voices/{voice}/sample` (JWT, tout utilisateur connecté).
- Table : `speech_preferences(user_id PK, voice, instructions, updated_at)` — migration **170** (`170-speech-preferences.xml`, 168 réservé F-212) ; rollback `dropTable`.
- Composants backend : paquet `fr.claudegateway.speech` (`SpeechProvider`, `OpenAiSpeechProvider`, `SpeechProperties`, `SpeechConfig`, `SpeechVoices`, `SpeechPreference*`, `SpeechController`, `SpeechSampleService`, `SpeechToolCatalog`, `SpeechToolExecutor`, `SpeechUsage`) ; `TranscriptionProvider` / `HttpTranscriptionProvider` (mode mots) ; `AtelierChatService` (mutateur, déclaration, routage, accord, cap par tour) ; `AccountService` (purge) ; `application.yml` (`app.speech`).
- Composants Angular (PR 2) : `VideoVoiceSettingsComponent` (carte des Paramètres), `SpeechService`.

## Plan de test
- Unitaires : `OpenAiSpeechProviderTest` (non configuré ⇒ aucun appel ; corps envoyé), `SpeechToolCatalogTest` (garde : coupe-circuit, clé, droit, cible poste), `SpeechToolExecutorTest` (résolution voix/consignes, nettoyage du nom, mots, transcription en échec non bloquante, auditions ⇒ publication, usage inscrit / non inscrit sur échec), `HttpTranscriptionProvider` mots (parsing).
- Intégration : `SpeechControllerIntegrationTest` (GET défaut, PUT/GET, 400 voix inconnue, DELETE, isolation : la préférence d'Alice invisible pour Bob, échantillon en cache, 503 coupe-circuit, 404 voix inconnue), migration appliquée (contexte Spring).
- Front (PR 2) : spec du composant (chargement, enregistrement, défaut, écoute).
- Suite complète backend avant merge.

## Préoccupations transversales
- **Plans / limites** : nouvel usage payant ⇒ `UsageLedgerService.recordTurn` (seul appelant nouveau : `SpeechUsage`) ; aucun quota/gate existant modifié ; borne de 20 appels par tour (`app.speech.max-per-turn`).
- **Contexte tenant** : le dépôt passe par `ProjectFileDeposit` avec le `Workspace` du tour (déjà vérifié possédé) ; préférence lue/écrite par le `user_id` du jeton (`CurrentUser.requireId`) ; usage rattaché à `user_id`. Composants : `SpeechToolExecutor`, `SpeechPreferenceService`, `SpeechController`, `SpeechUsage`.
- Auth / navigation : aucune nouvelle route publique ; la carte s'ajoute à l'écran Paramètres existant (aucune route Angular nouvelle).

## Hors périmètre
Voix fixée par série (SF-215-04), savoir-faire « cours vidéo » et briques (SF-215-03), rendu sur le poste (SF-215-02), Plume vidéo (SF-215-05), voix clonées / personnalisées, projet hébergé (sandbox), autre fournisseur que OpenAI (l'interface le permet).

## Notes et décisions (arbitrages réversibles)
- **Clé partagée avec le STT** plutôt qu'un nouveau secret (consigne de vague) : repli `APP_SPEECH_API_KEY` → `APP_STT_API_KEY`.
- **Mode mots forcé sur `whisper-1`** : seuls les modèles Whisper rendent l'horodatage par mot ; le modèle STT configuré reste inchangé pour la dictée.
- **Coût estimé, pas rapporté** : l'API de synthèse ne rend pas d'usage ; le coût est calculé (tarifs configurables) et marqué `CALCULATED`.
- **Échantillon mis en cache en mémoire** (par voix, consignes D2 fixes) : coût borné à 11 générations par instance.
