# Cadrage F-216 — Le coût d'une vidéo, annoncé avant et mesuré après

**Demande PO du 2026-10-25.** « À chaque génération de vidéo ou de série de vidéos, que le coût soit précalculé. On
confirme le coût en même temps qu'on confirme tout le reste. Ça permet aussi d'avoir une idée de ce qui est dépensé
là-dedans. » Le PO a demandé de suivre les préconisations ci-dessous.

## Constat (existant F-213/214/215, en prod)
- Le guide `cours-video.md` (§ 7.1) cite un **repère écrit** pour le plan d'une série : « 2 à 4 $ de fabrication +
  ~0,06 $ de voix par épisode de 2 à 3 min ». Rien n'est calculé, rien n'est exigé pour une vidéo unique, rien n'est
  mesuré après coup.
- `voix_off` inscrit déjà un coût calculé au journal d'usage (`SpeechUsage` → `UsageLedgerService` : 0,015 $ / 1 000
  caractères + 0,006 $ / min de transcription des mots).
- Chaque tour de la boucle de l'agent est mesuré : `usage_turns` (coût réel `provider_cost_usd`, une ligne par tour
  entier — mais aussi les lignes de la voix et de Plume) et `atelier_turn_measures` (tokens par nature, **seulement**
  les tours de la boucle, avec le modèle). Opus 5.5 en prod : ~1 $ par tour.
- La validation d'un plan passe déjà par la question structurée F-164 (`demander`).

## Principes
1. **Un devis calculé par le serveur, jamais deviné par le modèle** : c'est le résultat d'un outil.
2. **Une seule confirmation** : le devis est dans la question qui valide le plan (série) ou la fiche (vidéo unique).
3. **Le coût réel est mesuré, rattaché et visible**, à côté de l'estimation.
4. **Préfixe stable** : rien de volatil dans la consigne système ; les règles vont dans le guide chargé à la demande
   (re-déposé par `video_briques` à chaque appel) ; le devis est un résultat d'outil ; les définitions d'outils sont
   des textes fixes.
5. **Isolation `user_id`** partout, 404 croisés ; écrans conformes à `DESIGN_SYSTEM.md`.

## Décisions
### D1 — Outil `video_devis` (serveur)
Entrée : `episodes` (1 par défaut), `duree_min` (durée visée d'un épisode), `caracteres` (narration d'un épisode ;
défaut = durée × **900 caractères/min**, français posé), `retouches` prévues (1 par défaut), `serie` (titre,
facultatif), `video` (video_id : **devis de retouche** d'une vidéo publiée, durée lue sur la vidéo).

Sortie : fourchette **basse / haute** en dollars, ventilée en trois postes, plus durée totale et temps de rendu estimé,
une ligne prête à placer dans la question (« Coût estimé : 4,80 à 7,20 $ (voix 0,40 $ · agent 4,40 à 6,80 $ · rendu
0 $ — votre poste) ») et l'identifiant du devis.
- **(a) voix** : caractères × tarif de synthèse + minutes × tarif d'horodatage — **les tarifs de `voix_off`**
  (`app.speech.*`), jamais dupliqués. Borne haute : + une part de voix refaite par retouche
  (`retouche-voice-fraction`, 0,5).
- **(b) agent** : tours estimés × **coût moyen d'un tour du modèle courant**. Tours : `turns-per-minute-low/high`
  (0,8 / 1,6 par minute de vidéo, soit 2 à 4 tours pour 2 min 30 — le repère mesuré), + `turns-per-retouche-low/high`
  (0,5 / 1 par retouche et par épisode), + `series-planning-turns-low/high` (1 / 2, série seulement). Coût d'un tour :
  moyenne **du compte** sur **7 jours glissants** des tours de la boucle (`atelier_turn_measures`, modèle de
  `app.atelier.model`), chiffrée par le calculateur de coût existant (`ProviderCostCalculator`, grille F-133) ; moins de
  5 tours mesurés ⇒ valeur par défaut configurable (`default-turn-cost-usd`, 1,00 $). La source (mesuré / défaut) est
  dite dans le résultat.
- **(c) rendu** : **0 $ (votre poste)** ; temps estimé = minutes de vidéo × `render-minutes-per-video-minute` (2,8 :
  7 min pour 2 min 34 dans l'essai).
- Coefficients sous `app.videos.cost.*`, documentés dans `application.yml`.
- **Retouche** : `video_devis(video, retouches)` ; sous le seuil `retouche-confirm-threshold-usd` (3,00 $) le résultat
  dit d'**annoncer** le devis dans la réponse, sans question bloquante ; au-dessus, de le faire confirmer par `demander`.

### D2 — Confirmation unique (guide)
Le guide `cours-video.md` exige `video_devis` **avant toute fabrication, vidéo unique comprise** : pour une vidéo
unique, une **fiche** (titre, objectif, durée, voix, sources) et le devis validés par **une** question `demander` ;
pour une série, le devis remplace le repère écrit dans la question du plan. Retouche après des retours F-214 : petit
devis annoncé, question seulement au-dessus du seuil. Pas de deuxième clic.

### D3 — Coût réel : rattachement par le **devis ouvert du sujet**
Arbitrage (le plus fiable sans toucher la boucle de l'agent) :
- un devis est **ouvert** à sa création dans le sujet ; tout coût inscrit au journal d'usage **pour ce compte et ce
  sujet** (tours de l'agent, `voix_off`, Plume) pendant qu'il est ouvert lui est imputé — le tour qui a produit le
  devis compris (le plan fait partie de la vidéo) ;
- à la **publication** (`video_publier`), la version reçoit ce qui a été dépensé depuis la publication précédente du
  même devis ; le **tour qui publie** n'est inscrit au journal qu'à sa fin : son coût est ajouté à la (aux) version(s)
  publiée(s) pendant ce tour quand il arrive (parts égales s'il en a publié plusieurs) ;
- clôture : vidéo unique ou retouche ⇒ à la fin du tour qui la publie ; série ⇒ quand le nombre d'épisodes prévus est
  publié ; un nouveau devis (vidéo/série) remplace l'ancien ; un devis **inactif** depuis `idle-close-hours` (48 h) est
  clos ; un devis de retouche se superpose au devis de série en cours (le plus récent reçoit les coûts) et ne se
  rattache qu'aux versions de **sa** vidéo ;
- une vidéo publiée sans devis ouvert : « coût non mesuré ».

Limites assumées (l'écran dit « estimé » et « mesuré ») : un travail sans rapport fait dans le même sujet pendant un
devis ouvert lui est imputé ; un seul tour qui fabrique plusieurs épisodes partage son coût à parts égales.

### D4 — Affichage
- Galerie (carte) et sous le lecteur : « Coût : 5,63 $ (estimé 4,80 à 7,20 $) » ; série : total + détail par épisode.
- Récapitulatif du mois en **en-tête de la galerie** : « Ce mois-ci : 12,40 $ en vidéos » (tous sujets du compte ;
  somme de ce qu'ont coûté les devis ouverts ce mois-ci, plans abandonnés compris : c'est la dépense réelle).

## Préoccupations transversales
- **Plans / limites** : aucun nouveau gate, aucun nouvel usage payant (le devis ne coûte rien) ; on **lit** le journal
  d'usage. Composants : `UsageLedgerService` (écouteur après écriture, qui ne peut jamais faire échouer un relevé),
  `QuotaService.recordUsage`, `SpeechUsage`, `PlumeService` (appelants inchangés).
- **Contexte tenant** : devis, imputations et lectures filtrés par `user_id` (+ sujet du tour, déjà vérifié possédé).
  Composants : `VideoToolCatalog`, boucle `AtelierChatService`, `VideoController`.
- Auth / navigation : non (aucune route d'écran nouvelle).

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-216-01 | Devis : table `video_quotes`, `VideoCostProperties`, coût moyen d'un tour mesuré, outil `video_devis` (série, vidéo unique, retouche + seuil), guide « devis avant fabrication » ; tests | 🟢 |
| SF-216-02 | Coût réel : écouteur du journal d'usage, imputation au devis ouvert, rattachement aux versions à la publication, clôtures ; coût + estimation dans `GET /videos` et `GET /videos/{id}` ; récapitulatif du mois `GET /videos/costs` ; tests d'isolation | 🟠 |
| SF-216-03 | Écran : coût sur les cartes et sous le lecteur, total et détail de série, récapitulatif du mois en en-tête de galerie | 🟢 |

Ordre : 01 → 02 → 03.

## Hors périmètre
Refacturation au client final ; plafond de dépense par vidéo (blocage dur) ; auto-étalonnage des coefficients sur les
vidéos mesurées (piste suivante quand assez de mesures existeront) ; F-217 (son des notifications).
