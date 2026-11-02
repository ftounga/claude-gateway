# CADRAGE F-239 — Jarvis converse comme une personne

**Statut :** cadrage du 2026-11-02, **réécrit le même jour** après le recadrage du PO. Le premier cadrage
(« Jarvis entend juste ») corrigeait les défauts un par un ; celui-ci traite le problème d'ensemble.
Famille « Jarvis » : F-230 → F-238, **F-239**.

## Demande PO (2026-11-02)

Essai du matin (08:01-08:15, `/jarvis`, `CONV_STREAM`, écoute ElevenLabs `scribe_v2_realtime` en `fr`, cerveau rapide
Sonnet 5.5) : « catastrophe, l'outil devient inutilisable si je dois répéter tout le temps ; la dictée de Claude Code
me paraît 10 fois meilleure ». Puis :

> « Je dois avoir l'impression de parler avec une vraie personne. Si je dis bonjour, il dit "Bonjour Monsieur". Il me
> pose une question en retour ("votre journée a été… ?"), puis je dis "commençons". Dans l'esprit du film, ou d'un
> assistant, d'un ami avec qui tu causes. »

> « Très souvent il ne comprend pas du tout ce que j'ai demandé ; quand je vois ma transcription à l'écran, c'est
> vraiment mauvais. La dictée de Claude Code est très bonne : elle analyse ce que j'ai dit, puis le "traduit" pour
> savoir ce que j'ai voulu dire. Le message final est toujours correct. »

## Constat (base prod et code, 2026-11-02)

**Ce qui a été entendu** (`atelier_messages`, `via = JARVIS`, rôle USER) : « Bonjour, service. » (Bonjour Jarvis),
« Travis. » (Jarvis), « Bien, des cagibes. », « Est-ce que le client Cagibi… », « …l'architecture de Kajip. » (CAGIP),
« Sur le cluster, c'est assez ving… », « Allons chez le client et donnez- », « Vente chez le via Edenred. ».

| # | Défaut | Cause |
|---|---|---|
| 1 | Noms propres et mots massacrés | Le texte **en flux** (`scribe_v2_realtime`, sans vocabulaire) est **le texte envoyé**. Aucune retranscription de l'énoncé complet, aucun vocabulaire, aucune relecture. |
| 2 | « Je vous écoute », « Voyons cela, je vérifie Monsieur » | Formule de réveil sur « Jarvis » reconnu en fin de phrase ; formule d'attente à 1,2 s ; annonce d'office `AUTO_ANNOUNCE`. **Corrigé par SF-239-01/02.** |
| 3 | Tours fantômes (08:15, 110 s d'écoute) | Rien ne vérifiait qu'on avait parlé. **Corrigé par SF-239-01** (porte de parole réelle). |
| 4 | « Bonjour Jarvis » ⇒ bilan, garde d'ancrage, délégation à Opus, 23 s | Le cerveau rapide fait un point non demandé. **Corrigé par SF-239-02** (salutation jamais déléguée). |
| 5 | Phrases coupées | Fin de tour = 0,7 s de silence ; le premier segment validé clôt l'écoute. |
| 6 | Pas une conversation | Jarvis répond, mais ne converse pas : pas de réciproque, pas de transition, cite la transcription tronquée (« La phrase m'est parvenue tronquée : … ») au lieu de demander de répéter. |

## Premier banc d'écoute (2026-11-02, outil `tools/jarvis-stt-bench`)

24 énoncés : 12 phrases types du PO (noms propres, pauses de réflexion de 0,8 à 1,2 s) × 2 voix (George, et une voix
française), synthétisées par ElevenLabs, bruit de pièce léger. **Audio propre** : les erreurs réelles sont plus
nombreuses (micro, accent, hésitations) ; l'écart entre options est ce qui compte.

| Option (énoncé complet, à la fin du tour) | WER | Noms manqués | Latence p50 / p90 | € / mois (250 min) |
|---|---|---|---|---|
| Scribe v2 batch | 6,3 % | 10 | 700 / 865 ms | ≈ 0,84 € |
| **Scribe v2 batch + vocabulaire (keyterms)** | **0 %** | **0** | **630 / 789 ms** | ≈ 1,03 € (0,27 $/h) |
| gpt-transcribe | 7,1 % | 12 | 679 / 1 097 ms | ≈ 1,04 € |
| gpt-transcribe + vocabulaire (keywords + prompt) | 0,8 % | 0 | 682 / 994 ms | ≈ 1,04 € |
| gpt-4o-mini-transcribe + prompt (arrêt 26/02/2027) | 0 % | 0 | 685 / 863 ms | ≈ 0,69 € |
| whisper-1 (dictée actuelle) | 11 % | 17 | 1 023 / 2 022 ms | ≈ 1,38 € |

Lecture : **le vocabulaire fait tout** (6-11 % ⇒ 0-1,6 %) ; Scribe v2 batch + vocabulaire est le meilleur et le plus
rapide, chez le sous-traitant déjà retenu (D-PO-1 F-233). La latence mesurée ici (≈ 630 ms depuis un poste en France,
audio envoyé en une fois) dépasse la cible de 500 ms : SF-239-04 la réduit en envoyant l'audio **pendant** la parole
(seule la fin reste à envoyer) et la mesure en prod. Les résultats en flux (avec / sans vocabulaire) et la découpe par
silence (0,7 / 1,0 / 1,2 / 1,5 s) complètent le rapport du banc (SF-239-03).

## Décisions

| # | Décision |
|---|---|
| D1 | **Écoute = la dictée de Claude Code** : (1) le texte en flux ne sert qu'à l'affichage provisoire ; (2) l'**énoncé complet** est retranscrit à la fin du tour par le meilleur modèle batch (banc), **avec le vocabulaire de l'univers** du compte ; (3) une **relecture contextuelle** rend le texte voulu. |
| D2 | **Vocabulaire** : construit côté serveur depuis `JarvisUniverseService.universe(userId)` (clients, sujets, chantiers, étapes, personnes) + clés de tickets vues dans le fil + « Jarvis » ; filtré `user_id`, dédoublonné, **≤ 100 termes** (au-delà, Scribe facture 20 s minimum par requête), chaque terme ≤ 50 caractères et ≤ 5 mots (règles du fournisseur). Jamais dans une consigne système. |
| D3 | **Relecture sans latence ajoutée** (option étudiée en premier, demandée par le PO) : le cerveau rapide écrit, **dans le même appel**, une première ligne `compris: <la parole relue>` — noms propres, mots mal entendus, ponctuation — **sans jamais ajouter de demande**. Elle est affichée à l'écran, enregistrée comme message USER (la parole brute reste dans la mesure, sans contenu ailleurs) et c'est elle que reçoit Opus en cas de délégation. Garde déterministe : une relecture qui ajoute des mots d'action absents, ou s'écarte trop de la parole (distance d'édition par mot > 40 %), est rejetée ⇒ parole brute. Cerveau rapide éteint ⇒ **passe dédiée** courte (même consigne de relecture, modèle rapide), mesurée. Contexte volatil (vocabulaire, 2-3 derniers échanges) **dans le message**. |
| D4 | **Banc de conversation = porte de déploiement** : ~30 scénarios multi-tours (salutations, petite conversation, « commençons », point, demande métier, phrase mal entendue, interruption, silence, remerciement, au revoir) + les vrais tours du PO du 2026-11-02 ; rejoués sur le vrai cerveau rapide, notés par un juge (grille : naturel, concision, aucune formule robotique, aucune délégation inutile, sens juste, au plus une question en retour). **Seuil** : ≥ 90 % de scénarios réussis, 0 délégation inutile, 0 formule robotique, 0 fait non ancré. **Aucun déploiement Jarvis sans banc au vert** (ligne ajoutée à `release-checklist.md`). Extension de `POST /api/admin/jarvis/fast-brain/bench` (jeu `CONVERSATION`). |
| D5 | **Converser comme une personne** (D18 F-235 : majordome britannique, « Monsieur », vouvoiement, humour sec) : salutations réciproques ; **au plus une** petite question en retour, pas à chaque tour ; transitions (« Commençons. » ⇒ « Très bien, Monsieur. Par quoi souhaitez-vous commencer ? ») ; réponses orales courtes ; aucune formule répétée ; mémoire du fil **du jour** ; aucun bilan non demandé ; « Pardon, Monsieur, je n'ai pas saisi la fin. » au lieu de citer la transcription. Consigne stable, versionnée ; l'état du jour (première salutation, dernière question posée) dans le message. |
| D6 | **Tour de parole** : silence de fin de tour mesuré par le banc, prolongé quand la phrase est en suspens (trait d'union, virgule, conjonction, article, préposition) ; un segment validé en suspens ne clôt plus l'écoute. |
| D7 | **Correction visible** : le texte compris (relu) s'affiche ; un mot se corrige d'un toucher ou par « non, j'ai dit … » ; la correction repart comme la demande corrigée. |

## Découpage (ordre imposé par le PO : A, puis D, puis C, puis B)

| SF | Bloc | Objet | Estim. | État |
|---|---|---|---|---|
| SF-239-01 | — | **Ni formule creuse ni tour fantôme** (écran) | 2 j | **Livrée** (#1494) |
| SF-239-02 | — | **Une salutation ne se délègue jamais** (serveur) : consigne v3, garde d'invitation, annonce d'office vide, trace des refus | 1 j | Livrée |
| SF-239-03 | A | **Le banc d'écoute** : outil reproductible (`tools/jarvis-stt-bench`), phrases réelles du PO (prod du 2026-11-02) et synthétiques avec noms propres et hésitations ; WER avant / après relecture, taux de « sens compris », latence, € ; rapport et recommandation | 1,5 j | |
| SF-239-04 | A | **Retranscrire l'énoncé complet** : l'écran garde l'audio de l'écoute ; la passerelle le retranscrit (fournisseur batch derrière une interface, Scribe v2 par défaut) avec le vocabulaire de l'univers (D2) ; repli sur le texte en flux au-delà d'un délai ; compté en €, sous le plafond voix | 2 j | |
| SF-239-05 | A | **Comprendre ce qui a été voulu** : ligne `compris:` du cerveau rapide (D3), garde anti-invention, affichage, message USER relu ; passe dédiée si le cerveau rapide est éteint | 2 j | |
| SF-239-06 | A | **Corriger d'un toucher ou de la voix** (D7) | 1,5 j | |
| SF-239-07 | D | **Le banc de conversation, porte de déploiement** (D4) : 30 scénarios multi-tours + tours réels, juge et grille, seuil, ligne de release checklist | 2 j | |
| SF-239-08 | C | **Converser comme une personne** : consigne v4 (D5), état du jour dans le message, réplique « je n'ai pas saisi » | 2 j | |
| SF-239-09 | C | **La mémoire du jour** : le fil de la conversation du jour (au lieu de 30 min / 12 échanges), borné | 1 j | |
| SF-239-10 | B | **Laisser finir la phrase** (D6) | 1,5 j | |

Chaque SF de C et B passe le banc de conversation (SF-239-07) avant merge.

## Préoccupations transversales

- **Plans / limites** : retranscription et passe de relecture comptées dans `jarvis_usage_days` et le plafond voix
  (`JarvisVoiceService`), en €. Composants : `JarvisUsageService`, `JarvisVoiceService`, `JarvisFastBrainService`
  (plafond propre).
- **Contexte tenant** : vocabulaire et derniers échanges lus sous `user_id` (`JarvisUniverseService`,
  `AtelierMessageRepository` filtré) ; l'audio n'est ni conservé ni journalisé (régime F-145 / D8 F-231).
- **Auth / Principal** : un endpoint authentifié de plus (retranscription), accès Forge requis comme la voix.
- **Navigation** : inchangée.

## Configuration prod (à poser par l'exploitation, au fil des SF)

- `APP_JARVIS_UTTERANCE_PROVIDER=elevenlabs`, `APP_JARVIS_UTTERANCE_MODEL=scribe_v2` (SF-239-04 ; clé
  `APP_ELEVENLABS_API_KEY` déjà présente).
- Seuil de fin de tour (SF-239-10) : valeur issue du banc.

## Hors périmètre

Changer de voix ; F-237 / F-238 ; changer le cerveau Opus ou réduire le raisonnement.
