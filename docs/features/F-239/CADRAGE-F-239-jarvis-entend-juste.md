# CADRAGE F-239 — Jarvis entend juste

**Statut :** cadrage du 2026-11-02 · Famille « Jarvis » : F-230 → F-238, **F-239**.

## Demande PO (2026-11-02)

Essai du matin (08:01-08:05, `/jarvis`, mode `CONV_STREAM`, écoute ElevenLabs `scribe_v2_realtime` en `fr`, cerveau
rapide Sonnet 5.5) : « catastrophe, l'outil devient inutilisable si je dois répéter tout le temps ; la dictée de
Claude Code me paraît 10 fois meilleure ». Second témoignage du même matin : « Je viens encore de lui dire "Bonjour
Jarvis", il me dit "Voyons cela, je vérifie Monsieur". Ce n'est pas un assistant, on aurait dit un robot. C'est
systématique. » Troisième : « Quand je ne dis rien, on dirait qu'il comprend des tâches tout seul et essaie d'y
répondre. » — **priorité** : formules, tours fantômes et salutations traités en premier (SF-239-01 écran, SF-239-02
serveur), mergés seuls pour un déploiement rapide.

## Constat (vérifié en base prod et dans le code, 2026-11-02)

Paroles transcrites (`atelier_messages`, `via = JARVIS`, rôle USER) : « Bonjour, service. » (pour « Bonjour
Jarvis »), « Travis. », « Bien, des cagibes. », « Est-ce que le client Cagibi… », « …l'architecture de Kajip. »
(CAGIP), « Sur le cluster, c'est assez ving… », « Allons chez le client et donnez- », « Vente chez le via Edenred. ».
Réponse du cerveau rapide : « … Je vous écoute pour la suite de la journée. »

| # | Défaut | Cause (code) |
|---|---|---|
| 1a | « Je vous écoute. » avant toute réponse | `onWake()` joue une formule de réveil (`jarvis-phrases.ts`, kind `wake`) dès que le moteur sur l'appareil reconnaît le **mot isolé** « Jarvis » — y compris à la fin de « Bonjour Jarvis » ; la phrase dite avant (« Bonjour ») est perdue, le PO doit répéter. |
| 1b | « Voyons cela », « Je vérifie, Monsieur » à chaque parole | `armFastFiller()` : une formule d'attente **générique** à 1,2 s si la première phrase du cerveau rapide n'est pas prête ; or elle arrive en 2,3-3,4 s (`jarvis_turn_timings.fast_first_token_ms` du matin) ⇒ systématique, même pour « Bonjour ». Même mécanisme (`armFiller`) sur le chemin Opus direct. |
| 1c | « Je vous écoute » en conclusion | `JarvisFastBrainPrompt` (v2) n'interdit ces formules que « seules » ; la garde `isFillerAlone` ne retire qu'une phrase réduite à la formule. |
| 1d | « Je vérifie, Monsieur. » puis 23 s d'attente pour « Bonjour Jarvis » (08:11:52) | Le cerveau rapide ajoute un bilan non demandé ; la garde d'ancrage le refuse ⇒ **délégation automatique** (`auto`, raison `grounding`) avec l'annonce fixe `AUTO_ANNOUNCE` = « Je vérifie, Monsieur. ». |
| 1e | Tours fantômes (08:15:18 : 110 s d'écoute, 08:15:55 : 21 s ; `FAST_DELEGATED`, `grounding`, aucun message USER) | L'écoute en flux valide un texte né du bruit ; rien ne vérifie qu'on a parlé ; le cerveau rapide est appelé, échoue à l'ancrage, délègue ; sans sujet engagé (D19 F-235), l'écran ne lance pas le tour mais l'annonce est dite. Écoute non bornée. |
| 2 | Phrases coupées | Fin de tour = silence de 0,7 s (`JarvisSttStreamProperties.vadSilenceSeconds`, `END_OF_SPEECH_MS`) et le premier segment validé clôt l'écoute. |
| 3 | Noms propres mal compris | Aucun vocabulaire transmis au fournisseur ; aucune retranscription de l'énoncé complet. |
| 4 | Pas de correction | Le texte compris s'affiche (`said`) mais ne se corrige pas ; `jarvis_turn_timings.correction` ne compte qu'une parole qui contredit une réponse rapide. |

## Décisions

| # | Décision |
|---|---|
| D1 | **Aucune formule d'attente générique.** Le cerveau rapide répond seul (≈ 2-3 s) : silence, et le cœur montre qu'il réfléchit. Une délégation dit déjà son **annonce précise** (« Je regarde les tickets bloqués d'Edenred »). Sur le chemin Opus direct (cerveau rapide éteint, repli, confirmation), une phrase d'attente **seulement** après 2,5 s sans réponse, **une fois**, **liée à la demande** (sujet ou chantier nommé dans la parole : « Je regarde pour Edenred. »), jamais pour une salutation ni une politesse, jamais le même gabarit deux tours de suite ; sinon silence. |
| D2 | **Réveil** : « Jarvis » reconnu **dans une phrase** (parole juste avant, ou parole qui reprend dans les 0,6 s) ⇒ **aucune formule** ; l'audio de la phrase (au plus 4 s avant le mot, sans pause > 1 s) est **donné au début de l'écoute en flux**, pour que « Bonjour Jarvis » soit transcrit et reçoive sa réponse. « Jarvis » **seul** ⇒ une formule courte : « Oui ? », « Oui, Monsieur ? », « Dites-moi. », « Allez-y. » — jamais « Je vous écoute » ni « À votre écoute ». |
| D3 | **Consigne du cerveau rapide v3** : ces formules sont interdites partout, y compris en conclusion ; garde déterministe qui retire une phrase d'invitation (« Je vous écoute… », « Je reste à votre disposition… »). **Une salutation ou une politesse ne se délègue jamais** : réponse courte, sans bilan non demandé ; une phrase non ancrée y est **retirée** (pas de délégation) ; à défaut de phrase sûre, une réponse fixe courtoise. L'annonce automatique creuse disparaît (silence + animation). Texte fixe, versionné, aucun contenu volatil. |
| D3b | **Aucun tour sans parole réelle** : porte avant tout traitement (vide, bruit, hallucinations typiques du STT, sans voix au micro, invraisemblable) ⇒ aucun tour, `falseTriggers` compté, trace sans contenu (`rejected_kind`, `rejected_chars`) ; écoute bornée à 30 s ; veille après 60 s sans parole acceptée. |
| D4 | **Fin de tour** : silence par défaut porté à la valeur mesurée par le banc, prolongé quand la phrase est **en suspens** (trait d'union, virgule, conjonction, article, préposition) ; un segment validé ne clôt plus l'écoute s'il est en suspens. |
| D5 | **Transcription** : option choisie **sur mesure** (banc SF-239-03) parmi : retranscription de l'énoncé complet par un modèle batch, vocabulaire injecté depuis l'univers du compte (filtré `user_id`, borné), ou les deux. Cible : latence ajoutée ≤ 500 ms ; justesse avant coût ; coût en €. |
| D6 | **Correction visible** : le texte compris reste affiché ; un mot se corrige d'un toucher, ou par « non, j'ai dit … » ; la correction repart comme une demande corrigée. |

## Découpage

| SF | Objet | Estim. |
|---|---|---|
| SF-239-01 | **Ni formule creuse ni tour fantôme** (écran) : D1 + D2 + porte D3b ; test « Bonjour Jarvis » ⇒ aucune formule, seulement la réponse. Front seul (déploiement sans couper de tour). | 2 j |
| SF-239-02 | **Une salutation ne se délègue jamais** (serveur) : consigne v3, garde d'invitation, salutation sans délégation, annonce automatique retirée, trace des paroles refusées (D3, D3b). | 1 j |
| SF-239-03 | **Le banc d'écoute** : phrases types synthétisées (voix George + une voix française), WER, erreurs de noms, latence, coût par option ; recommandation (D5) et seuil de fin de tour (D4). | 1 j |
| SF-239-04 | **Laisser finir la phrase** (D4). | 1,5 j |
| SF-239-05 | **Entendre juste** : l'option retenue par le banc (D5). | 2 j |
| SF-239-06 | **Corriger d'un toucher ou de la voix** (D6). | 1,5 j |

## Préoccupations transversales

- **Plans / limites** : un éventuel appel batch supplémentaire par tour est compté dans `jarvis_usage_days` et le
  plafond voix existant (`JarvisVoiceService`), en €.
- **Contexte tenant** : le vocabulaire est construit depuis `JarvisUniverseService.universe(userId)` (filtré
  `user_id`) ; aucun endpoint ne prend d'identifiant de compte.
- **Auth / Principal**, **Navigation** : inchangés (aucune route nouvelle).

## Hors périmètre

Changer de voix ; F-237 / F-238 ; changer le cerveau ou réduire le raisonnement.
