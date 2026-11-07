# CADRAGE F-250 — Le visage de Jarvis

**Statut :** cadrage du 2026-11-07, **maquette en attente du goût PO**. Aucun code produit.
Famille « Jarvis » : F-230 → F-248, **F-250** (F-249 est pris par une autre feature, hors Jarvis 3D).
Base vérifiée : `origin/main` du 2026-11-07.
**Maquette :** `docs/features/F-250/maquette-visage-jarvis.html` (un seul fichier, `three@0.160.0` depuis jsDelivr,
ouverture sans erreur console vérifiée en Chrome sans tête). **Captures :** `docs/features/F-250/captures/`.
**Estimation :** 8 SF, ≈ 12,5 j, aucune au-delà de 2 j.

---

## 1. Le besoin (PO, 2026-11-07)

Quand Jarvis parle, montrer **un visage qui parle**, sous forme d'interface **neuronale / holographique** (comme dans
les films du genre) : « beaucoup plus sexy, plus joli ». Le PO choisit le **niveau 2 : synchronisation labiale
réelle** — les lèvres articulent selon les phonèmes, pas un simple battement sur le volume : « je veux ce qui est le
plus joli ».

### 1.1 Règles qui bornent ce cadrage (non négociables)

| Règle | Conséquence ici |
|---|---|
| **Justesse avant coût / rien dans le chemin du tour** | Le visage ne touche ni le cerveau, ni la consigne, ni le préfixe ; il lit des signaux qui existent déjà côté écran. |
| **Aucun appel payant ajouté** | L'alignement vient **de la même requête de voix** (§3) ; les visèmes sont calculés **sur l'appareil**. |
| **Provider Independence** | L'alignement est une capacité **optionnelle** de `SpeechProvider` ; une voix qui ne la fournit pas garde le visage en **repli amplitude**. |
| **Visage générique** | Géométrie **générée par code** (ovale, proportions moyennes, reliefs gaussiens) : jamais une personne réelle, jamais un personnage existant, aucun maillage importé, aucune photo. |
| **Premier mot de la voix intouché** | L'alignement est lu **pendant** que l'audio arrive ; aucune attente ajoutée avant le premier son (porte P1, §8). |
| **DESIGN_SYSTEM §23** | Palette holographique de `/jarvis` (orange = normal, violet = Opus travaille, cyan = attend le PO). Le style B de la maquette s'en écarte : c'est la question de goût (§10). |

---

## 2. L'existant, vérifié dans le code

| Brique | Ce qu'elle fait aujourd'hui | Où |
|---|---|---|
| Voix ElevenLabs | `POST {base}/v1/text-to-speech/{voice}/stream?output_format=mp3_44100_128`, corps `{text, model_id (défaut eleven_flash_v2_5), language_code: "fr"}` ; **recopie les octets mp3**, rien d'autre. **Aucun alignement n'est demandé ni relayé.** | `speech/elevenlabs/ElevenLabsSpeechProvider.java` (`synthesizeStream`) |
| Contrat de voix | `synthesize` / `synthesizeStream(text, voice, instructions, OutputStream)` : de l'audio, pas de minutage. | `speech/SpeechProvider.java` |
| Choix du fournisseur | `app.jarvis.voice.provider` = `${APP_JARVIS_VOICE_PROVIDER:openai}` ; ElevenLabs est choisi par configuration / audition (SF-233-06/07). La voix OpenAI `gpt-4o-mini-tts` s'arrête le **6 janvier 2027**. | `JarvisVoiceProperties`, `application.yml` l. 707, `JarvisAuditionService` |
| Lecture côté écran | `fetch('/api/jarvis/speech', Accept: audio/mpeg)` → **MediaSource** (mp3 en flux) ; une phrase à la fois, la suivante préchargée. | `core/services/jarvis-voice.service.ts` |
| Niveau sonore | `AnalyserNode` (`fftSize` 512) entre l'audio et la sortie ; `level()` = RMS 0-1 ; l'orbe et le cœur 3D le lisent déjà (`orbLevel`). | `jarvis-voice.service.ts` `level()`, `jarvis.component.ts` l. 336 |
| Crochets de lecture | `SpeakHooks.onFirstByte` / `onPlaying` ; `sayingHooks(sentence)` → `scenes.saying(sentence)` au **niveau de la phrase** (F-234, utilisé par F-248). | `jarvis.component.ts` l. 3059 |
| Clips de réaction | Phrases fixes pré-générées (`GET /api/jarvis/clips/{name}`), mp3 seul. | `JarvisVoiceController`, `JarvisClipService` |
| Moteur 3D | `three@0.186.1` (npm), cœur `Points` + `ShaderMaterial` piloté par uniformes, `HeartMood` = repos / écoute / réflexion / parole / coupure / oui / veille, bloom sélectif, qualité adaptative 3 niveaux, repli 2D. | `jarvis/stage3d/` (`jarvis-heart.ts`, `jarvis-post-chain.ts`, `jarvis-quality.ts`, `jarvis-render-capability.ts`) |

### 2.1 Ce que fournit vraiment ElevenLabs (documentation officielle, vérifiée le 2026-11-07)

- **`POST /v1/text-to-speech/{voice_id}/stream/with-timestamps`** : **même corps** que `/stream` (`text`, `model_id`,
  `language_code`…), même `output_format`. La réponse est un **flux d'objets JSON**, chacun :
  `{ "audio_base64": "...", "alignment": { "characters": [...], "character_start_times_seconds": [...],
  "character_end_times_seconds": [...] }, "normalized_alignment": { ...même forme... } }`.
- `normalized_alignment` porte le texte **tel que prononcé** (nombres et sigles développés) : c'est lui qu'on lit
  pour les visèmes (« 10 » → « dix »), `alignment` sert aux sous-titres.
- **Coût** : la facturation ElevenLabs est **au caractère** ; l'alignement est un champ de la réponse, pas un appel
  de plus. La page ne chiffre pas de différence de prix : **SF-250-01 le vérifie** sur la consommation du compte
  (porte P0).
- **Découpage du flux** : la documentation dit « un flux de JSON » sans préciser le séparateur (les SDK officiels le
  lisent ligne à ligne). **SF-250-01 commence par un banc** qui enregistre une réponse réelle avant d'écrire le
  lecteur.
- Le chemin WebSocket `stream-input` fournit aussi un alignement ; **non retenu** (notre voix est en HTTP, une
  requête par phrase, et F-233 l'a mesurée ainsi).

**Conclusion** : notre chemin de voix **peut** fournir un minutage par caractère **sans appel payant
supplémentaire**, mais **ne le fait pas aujourd'hui** : il faut passer à l'endpoint `with-timestamps` et relayer
l'alignement jusqu'à l'écran. Avec la voix OpenAI (ou une voix locale), il n'y a **pas** d'alignement : repli
amplitude.

---

## 3. Chaîne cible : du texte aligné aux lèvres

```
ElevenLabs /stream/with-timestamps ──► passerelle (relais, aucun calcul) ──► écran
   {audio_base64, alignment,               NDJSON par phrase :                 MediaSource ← audio
    normalized_alignment}                  {a: mp3 base64, c, s, e}            frise de visèmes ← alignement
                                                                               horloge = audio.currentTime
                                                                               uniformes de la bouche ← visèmes
```

1. **Passerelle** (SF-250-01) : capacité optionnelle `AlignedSpeech` à côté de `SpeechProvider`
   (`synthesizeAligned(text, voice, instructions, AlignedSink)`), implémentée par ElevenLabs. `POST /api/jarvis/speech`
   répond en `application/x-ndjson` **si** l'écran l'accepte (`Accept`) **et** si la voix sait aligner ; sinon mp3
   comme aujourd'hui. Les morceaux sont **relayés dès leur arrivée** (le premier contient du son). Comptage du
   plafond inchangé (caractères). Coupe-circuit `APP_JARVIS_FACE_ALIGN_ENABLED`.
2. **Écran** (SF-250-02) : `jarvis-voice.service.ts` décode l'audio en base64 et l'ajoute au `SourceBuffer` comme
   aujourd'hui ; il garde l'alignement de la phrase en cours. **L'horloge est `audio.currentTime`** (jamais l'heure
   murale) : les lèvres restent calées même si le réseau hoquette.
3. **Visèmes** (SF-250-03, pur) : caractères alignés → graphèmes français → **13 visèmes + silence** → frise
   `{visème, t0, t1}`.
4. **Bouche** (SF-250-04/06) : quatre paramètres (ouverture, étirement, arrondi, fermeture) envoyés en uniformes ;
   la déformation se fait **dans le shader de sommets** (CPU ≈ 0, comme T7 de F-236).

### 3.1 Table des visèmes (français)

| Visème | Graphèmes (après règles) | Ouverture | Étirement | Arrondi | Fermeture | Dominance |
|---|---|---|---|---|---|---|
| Silence | ponctuation, pauses | 0,02 | 0 | 0 | 0,15 | 0,5 |
| **M B P** | m, b, p, mm, pp | 0 | 0 | 0,05 | **1** | **4** |
| **F V** | f, v, ph, ff | 0,12 | 0,15 | 0 | 0,35 | 2,2 |
| **A** | a, à, â ; an, am, en, em (nasales) | 0,9 | 0,15 | 0 | 0 | 1 |
| **É È** | e, é, è, ê, ai, ei ; in, ain, ein, un | 0,5 | 0,45 | 0 | 0 | 1 |
| **I** | i, î, y | 0,22 | 0,9 | 0 | 0 | 1,1 |
| **O** | o, ô, au, eau ; on, om | 0,55 | −0,1 | 0,7 | 0 | 1,1 |
| **OU** | ou, w ; début de oi, oin | 0,18 | −0,25 | 1 | 0 | 1,6 |
| **U** | u, û, ù | 0,15 | −0,2 | 0,95 | 0 | 1,5 |
| **EU** | eu, œu, œ | 0,32 | 0 | 0,6 | 0 | 1,1 |
| **T D N L** | t, d, n, l, gn, ll, nn, tt | 0,24 | 0,25 | 0 | 0 | 0,6 |
| **S Z** | s, z, ç, ss, c doux | 0,09 | 0,55 | 0 | 0 | 0,9 |
| **CH J** | ch, j, g doux | 0,22 | −0,05 | 0,65 | 0 | 1,2 |
| **K G R** | k, q, qu, c/g durs, r, rr | 0,38 | 0,1 | 0 | 0 | 0,5 |

**Règles graphème → phonème (simples, déterministes)** : graphèmes multiples d'abord (`eau`, `ain`, `ou`, `oi`,
`ch`, `gn`, `qu`…) ; **nasale** seulement si la lettre suivante n'est ni voyelle ni `n`/`m` (« réunion » → nasale
finale ; « minutes » → i + n) ; `c`/`g` doux devant e, i, y ; `h` muet ; finales muettes `s t d x z p` ; `e` muet en
fin de mot et devant `-s`/`-nt` ; `s` entre voyelles → S Z ; `x` → K + S ; `oi` → OU puis A sur le même intervalle.
Les cas non couverts (liaisons, mots étrangers) **ne cassent rien** : le minutage reste celui d'ElevenLabs, seule la
forme de bouche est approximative.

**Co-articulation** : à l'instant t, chaque événement voisin pèse `dominance × exp(−((t − centre)/(demi-durée + 40 ms))²)`
; les paramètres sont la moyenne pondérée, puis lissés par un ressort (≈ 30 s⁻¹). Une fermeture M B P l'emporte sur
ses voisines (dominance 4) : la bouche se ferme vraiment sur « Bonjour », « Monsieur », « commence ». Les lèvres
sont en **avance de 30 ms** sur le son (la parole se voit avant de s'entendre ; au-delà de 45 ms de retard l'œil le
remarque).

**Repli amplitude** : sans alignement (voix OpenAI, voix locale `speechSynthesis`, clip sans alignement, flux
interrompu), la bouche suit `level()` : ouverture seule, sans forme. La maquette montre la différence (case « Repli
amplitude »). Les **clips de réaction** reçoivent leur alignement **à leur génération** (fichier compagnon, une fois),
sinon repli amplitude.

---

## 4. Le rendu

Maquette = référence visuelle ; le produit reprend la même recette dans `jarvis/stage3d/` :

| Élément | Recette |
|---|---|
| **Surface** | Grille régulière en projection frontale (pas 0,019 PC / 0,028 téléphone), profondeur = ovale + reliefs gaussiens (arcades, orbites, arête du nez, pommettes, lèvres, menton). ≈ 5 900 points PC, ≈ 2 900 téléphone. |
| **Nuage de points** | `Points` + `ShaderMaterial` additif ; éclairage factice précalculé (lumière haut-gauche + liseré de bord) pour que le relief se lise ; scintillement par point. |
| **Fil de fer** | Une maille sur deux, triangulée, **partageant les sommets** des points ; coupée sur la fente de la bouche pour qu'elle s'ouvre vraiment. |
| **Traits lumineux** | Contours des lèvres (4 courbes, arc de Cupidon), paupières, iris, sourcils, arête du nez : « incandescents » (`--cg-holo-core`). |
| **Lueurs** | Bloom (`UnrealBloomPass` existant, seuil bas pour épargner le fond), lueur de bouche à la parole, balayage horizontal lent, bords qui se dissolvent en particules. |
| **Décor** | Aura de particules (shader seul), cadran tournant derrière la tête, socle de projecteur sous le menton, ondes : **convergentes** à l'écoute, **émises** par la bouche à la parole. |

### 4.1 États (correspondance avec `HeartMood`)

| État | Visage |
|---|---|
| **Repos** | Respiration (4,2 s), balancement lent de la tête, clignements (2,4-6 s), regard qui flâne, balayage lent. |
| **Écoute** | Yeux grand ouverts, sourcils levés, tête inclinée vers le PO, ondes qui **convergent** (amplitude = micro). |
| **Réflexion** | Paupières mi-closes, regard en haut à gauche, un sourcil levé, **éclairs synaptiques violets** sur la surface, aura qui tourbillonne. |
| **Parole** | Lèvres synchronisées, lueur de bouche, ondes émises, légers hochements avec l'ouverture. |
| **Coupure** | La bouche se ferme en 120 ms, regard vers le PO (T-C6 de F-236). |
| **« Oui » attendu** | Teinte d'attente (cyan) sur les traits, ondes lentes (T-C5). |
| **Veille** | Points éteints à 20 %, plus de fil de fer, respiration lente. |

---

## 5. Cohabitation avec l'existant

| Brique | Règle |
|---|---|
| **Cœur 3D (F-236)** | Le visage **est** le cœur quand Jarvis dialogue : les particules du cœur se **recomposent** en visage (transition T-F1, 900 ms, deux attributs de position interpolés, comme le déploiement T7) et redeviennent cœur au repos prolongé (> 60 s) ou à la fermeture. Réglage par appareil « Jarvis : visage / cœur » (défaut **visage**). |
| **Grand cerveau (F-235)** | Quand Opus travaille longtemps, le visage se dissout vers la scène des deux cerveaux ; il revient pour parler. |
| **Constellation / univers (F-237)** | Le visage occupe la place du cœur, à l'échelle 0,8 en vue d'ensemble ; liaisons et nœuds inchangés. |
| **Pancartes (F-248)** | À l'apparition d'une pancarte, le visage glisse sur le côté (échelle 0,6) et **tourne le regard vers elle** ; aucune pancarte n'est masquée. |
| **Sous-titres, HUD** | Inchangés ; le visage est décoratif (`aria-hidden`), l'état reste écrit en mot. |
| **Téléphone / compagnon (WebView F-232)** | LOD « Bas » : moitié moins de points, fil de fer une maille sur deux, sans aura ni aberration ; ≥ 50 i/s. |
| **Repli 2D, mode sobre, `prefers-reduced-motion`** | Règle T10 de F-236 inchangée : **pas de visage**, orbe 2D. |

---

## 6. Performance

| Mesure | Téléphone | PC |
|---|---|---|
| Images/s pendant la parole | p50 ≥ 50 | p50 ≥ 60 |
| CPU par image ajouté par le visage | ≤ 0,5 ms (déformation dans le shader) | ≤ 0,5 ms |
| Points / segments | ≈ 2 900 / 2 500 | ≈ 5 900 / 6 000 |
| Allocations en régime | 0 | 0 |
| Appels de dessin ajoutés | ≤ 4 | ≤ 6 |

La maquette fait la déformation **sur le CPU** (lisibilité) ; le produit la passe dans le shader.
Qualité adaptative §6.3 de F-236 reconduite (pas, aura, bloom, aberration).

## 7. Accessibilité

- Visage décoratif (`aria-hidden`), état écrit, sous-titres conservés.
- `prefers-reduced-motion` / mode sobre ⇒ pas de 3D (T10) ; aucun clignotement > 3 Hz (balayage et éclairs lents).
- Contraste des textes du HUD inchangé (§23, ≥ 5,8:1).

## 8. Portes (bloquantes)

| # | Porte |
|---|---|
| **P0** | Aucun coût ajouté : consommation ElevenLabs identique à texte égal, `/stream` vs `/stream/with-timestamps` (mesuré sur 50 phrases). |
| **P1** | Premier son de Jarvis ≤ **+20 ms p95** par rapport au chemin mp3 (chronomètre SF-233-01). Sinon : alignement coupé, repli amplitude. |
| **P2** | Écart lèvres ↔ son ≤ **45 ms p90** (frise vs `audio.currentTime`, banc de synchronisation). |
| **P3** | Images/s §6 tenues pendant la parole, sur le téléphone du PO et le PC. |
| **P4** | Repli amplitude automatique, sans erreur, si l'alignement manque ou est mal formé. |
| **P5** | Coupe-circuits `APP_JARVIS_FACE_ENABLED` (visage) et `APP_JARVIS_FACE_ALIGN_ENABLED` (alignement). |

---

## 9. Découpage — SF-250-01 → SF-250-08 (≈ 12,5 j)

Les mini-specs (`subfeature-template.md`) sont rédigées au démarrage de chaque SF, **après** le choix du style.

| SF | Titre | Contenu | Est. |
|---|---|---|---|
| **SF-250-01** | Relayer l'alignement | Banc d'abord (réponse réelle enregistrée, séparateur, P0) ; capacité `AlignedSpeech` (ElevenLabs), `POST /api/jarvis/speech` en NDJSON si accepté, mp3 sinon ; plafond inchangé ; coupe-circuit. Tests : relais morceau par morceau, voix sans alignement ⇒ mp3, plafond, isolation `user_id` (chemin existant). | 1,5 j |
| **SF-250-02** | Lecture alignée | `jarvis-voice.service.ts` : NDJSON → `SourceBuffer` + alignement de la phrase, horloge `audio.currentTime`, repli mp3 ; P1 mesurée. | 1,5 j |
| **SF-250-03** | Les visèmes | `jarvis-visemes.ts` pur : règles graphème → visème, `normalized_alignment`, co-articulation, avance 30 ms ; tests unitaires (phrase EDENRED, nombres, nasales, finales muettes, `oi`, `x`). | 1,5 j |
| **SF-250-04** | Le visage 3D | `jarvis-face.ts` : géométrie générée, points + fil de fer + traits, déformation dans le shader, style retenu, LOD 3 niveaux, 0 allocation. Banc `?banc=visage`. | 2 j |
| **SF-250-05** | Le visage vit | États `HeartMood` (§4.1), clignements, regard, ondes, éclairs ; transition T-F1 cœur ↔ visage ; coupure. | 2 j |
| **SF-250-06** | Les lèvres dans l'écran | Brancher frise → uniformes ; repli `level()` ; clips (alignement à la génération) ; voix locale ⇒ amplitude ; P2. | 1,5 j |
| **SF-250-07** | Cohabitation | Pancartes (glisse et regard), grand cerveau, univers, cockpit PC, téléphone, compagnon WebView, réglage « visage / cœur » ; non-régression des scènes F-234/F-236. | 1,5 j |
| **SF-250-08** | Bancs et planche | Bancs `VISAGE` (captures déterministes), perf (P3), synchronisation (P2) ; planche PO. | 1 j |

**Préoccupations transversales** : aucune touche à l'auth, au tenant, aux plans ni au routage. Le chemin
`POST /api/jarvis/speech` reste sous `atelierAccess.requireAccess()` et `currentUser.requireId()` (inchangés) ;
composants impactés listés ci-dessus (`JarvisVoiceController`, `JarvisVoiceService`, `ElevenLabsSpeechProvider`,
`jarvis-voice.service.ts`, `jarvis-stage-3d.component.ts`, `jarvis-heart.ts`, `jarvis-stage-director.ts`).

## 10. Question de goût au PO (une seule)

**Quel style de visage ?** (maquette, boutons A / B / C)

- **A · Ambre filaire** — orange de la marque, conforme à §23 (**défaut si pas de réponse**).
- **B · Cyan holographique** — le « look film » ; demande d'amender §23 **pour le visage seul**, car le cyan y veut
  dire « attend le PO » (l'attente passerait alors en orange sur le visage).
- **C · Braise et glace** — points orange, fil de fer cyan léger, traits incandescents.

Tout le reste est tranché par défaut (§3-§8) et modifiable.

## 11. Hors périmètre

- Visage réaliste, avatar photo, clonage d'un visage ou d'un personnage, expression d'émotions fines.
- Modèle d'IA de synchronisation labiale (audio → visèmes) ou service payant de visage animé.
- Visage en 2D, dans le repli sobre ou en présentation.
- Langues autres que le français pour les règles de visèmes (le minutage, lui, marche pour toute langue).

## 12. Risques

| Risque | Parade |
|---|---|
| Séparateur du flux `with-timestamps` non documenté | Banc d'abord (SF-250-01). |
| Premier son plus lent avec `with-timestamps` | Porte P1 ; coupe-circuit ⇒ chemin mp3 actuel. |
| Base64 : +33 % de données | ≈ 170 kbit/s au lieu de 128 : négligeable en 4G ; mesuré en SF-250-02. |
| Effet « vallée de l'étrange » | Style holographique assumé, jamais réaliste ; goût PO sur maquette. |
| Voix OpenAI ou locale | Repli amplitude, sans erreur. |
| Lourdeur sur le téléphone | LOD Bas, déformation GPU, P3 bloquante. |
