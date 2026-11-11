# Cadrage F-256 — Banc à vraie voix de Jarvis

**Statut :** cadrage du 2026-11-11 · famille « Jarvis » (F-233, F-239, F-251, F-252, F-253 ; F-255 rejeu de séance et
F-257 converse en flux, en cours ailleurs) · **outil de mesure : aucune consigne, aucun outil, aucun modèle ni aucune
ligne de la boucle vocale ne change.**

## 1. Pourquoi (décision PO du 2026-11-11 : « changement de méthode »)

Jarvis déçoit en usage réel : phrases non entendues, transcription en direct en échec une fois sur deux depuis le 09/11
(31 `stream_fallback ouverture_fermee` contre 0 le 07/11), réponses répétées, mauvais sujet, premier son à 3,3-6,6 s,
« Voici » sans rien afficher. **Aucun banc n'a vu la régression** : `JarvisConversationBenchLiveTest` et
`JarvisPiecesBenchLiveTest` testent le cerveau rapide sur du **texte** ; `frontend/scripts/banc-jarvis-montre/` teste
l'écran avec une API **simulée**. Le PO était le seul testeur de la boucle vocale.

**Nouvelle règle de méthode : plus aucune mise en ligne de Jarvis sans un banc qui lui fait entendre de vraies voix,
avec des objectifs chiffrés.**

## 2. Ce que fait le banc

Une commande (`frontend/scripts/banc-jarvis-voix/lancer.sh`) :

1. lance une **passerelle locale** (jar du backend, profil `dev`, H2 neuve) avec la **configuration non secrète de la
   prod** (configmap `backend-config` filtrée sur Jarvis / voix / écoute : modèle du cerveau rapide, voix, flux) et les
   **vrais fournisseurs** (Anthropic, ElevenLabs, OpenAI) — clés lues **à la volée** dans le secret `backend-secrets`,
   jamais écrites, affichées ni journalisées ; secret JWT local tiré à chaque lancement ;
2. **amorce** un compte de test ADMIN (comme le PO) et un univers **fictif** de la forme réelle : CAGIP (agenor,
   data-platform), EDENRED (waf) ;
3. ouvre la **vraie page `/jarvis`** (build de développement) dans Chrome sans tête — vrai `jarvis.component`, vraie
   détection de voix, vrai flux ElevenLabs et vrai repli batch, vrai cerveau rapide, vraie voix, vrai affichage ;
4. lui fait **entendre** des enregistrements au micro, phrase par phrase, quand Jarvis écoute (ou à l'instant voulu
   pour un enchaînement rapide ou une coupure pendant qu'il parle) ;
5. mesure **tour par tour** et écrit `rapport.json` + `resume.txt` (hors dépôt), comparables d'une version à l'autre.

### 2.1 Le micro : virtuel, et pourquoi (vérifié le 2026-11-11)

`--use-file-for-fake-audio-capture` **relit le fichier depuis le début à chaque ouverture du micro** (essai : rampe
d'amplitude 1→20 ; après fermeture et réouverture on réentend la seconde 1). Une boucle qui rouvre le micro rejouerait la
première phrase. Le banc injecte donc, **avant le code de la page et seulement dans le banc**, un micro virtuel :
chaque `getUserMedia({audio})` rend une piste d'un même mélangeur WebAudio où le banc joue les phrases à l'heure qu'il
décide ; rouvrir le micro ne rejoue rien, comme un vrai micro. Le module de capture de la page (worklet, cadence des
blocs) est le vrai.

**Témoin** (scénario `temoin`) : la même phrase lue par le micro factice **de Chrome** (fichier), sans le mélangeur du
banc. Il reproduit exactement les mêmes fermetures du flux : le banc n'invente rien.

**Vue 2D par défaut** (`--vue 3d` sinon) : le poste du banc n'a pas de GPU ; la 3D en rendu logiciel (swiftshader)
occupe le fil principal et gonfle toutes les latences — un biais du banc. La 3D reste mesurable à part : elle change
aussi le comportement du flux (§7).

**Les sondes partent au fil de l'eau** vers le banc (fonction exposée par puppeteer) : un rechargement de la page en
plein scénario ne perd rien, et il est compté (document neuf).

**Voix de Jarvis au haut-parleur** : `echo` > 0 renvoie la voix jouée dans le micro **sans annulation d'écho** (cas le
plus dur) pour le scénario de coupure.

### 2.2 Ce qui est mesuré, par tour (une phrase jouée)

| Mesure | Source |
|---|---|
| Entendue ou perdue ; texte compris vs attendu (WER, hésitations ignorées) | dernier `POST /api/jarvis/converse` non jeté (ou envoi à Opus) dans la fenêtre du tour |
| Mode d'écoute (`CONV_STREAM`/`CONV_BATCH`), **repli** et sa raison, fournisseur retenu | `POST /api/jarvis/timings` de l'écran |
| Sessions du flux : ouverture, **code et raison de fermeture**, erreurs du fournisseur, segments validés | sonde WebSocket dans la page |
| **Premier son après la fin réelle de parole** (fin lue dans l'enregistrement, pas l'horloge de l'écran) ; premier son de voix (hors clip) ; valeur déclarée par l'écran à côté | sondes `playing` des éléments audio |
| Réponse **répétée** (phrase de ≥ 3 mots déjà dite dans le scénario, ou deux fois dans le tour) | corps de `POST /api/jarvis/speech` |
| Ordres d'écran demandés (`show`) vs affichés (`show`) / refusés (`show_refused`) ; **« Voici »/« Voilà » sans affichage** | flux SSE du cerveau rapide, journal d'écoute |
| Phrases rejetées par l'écran (`speech_rejected` et motif) | journal d'écoute |
| Coût du passage en € | compteur Jarvis de la passerelle (`GET /api/jarvis/costs`, avant / après) |

**Une seule session de transcription à la fois** : les scénarios passent l'un après l'autre (le compte ElevenLabs est
partagé avec la prod ; l'enquête du 11/11 soupçonne une limite de débit ou de simultanéité).

## 3. Corpus — hors dépôt (`~/.local/share/claude-gateway/jarvis-voice-corpus/`, droits 700)

- **Vraies captures du PO** (`captures-prod/`) : les **12** lignes de `jarvis_audio_captures` (09/11, rétention 7 j en
  prod, disparition vers le 16/11) sauvées le 11/11 en **lecture seule** (pod temporaire `amazon/aws-cli` sous le
  compte de service IRSA du backend, `aws s3 cp`, puis supprimé ; lignes lues par un pod `psql` en transaction lecture
  seule). 12/12 fichiers WAV 16 kHz récupérés (et pas 7 : les rejets batch ont aussi leur audio), étiquettes dans
  `labels.json` (`stream_text`, `final_text`). **Jamais dans git** : voix et contenu client.
  - 5 captures portent un texte ; **7 sont vides** : l'analyse d'activité montre ≤ 0,5 s de son, souvent à la toute fin
    d'un enregistrement de 5 à 22 s de quasi-silence (crête 800-7 500 contre 6 500-14 000 pour une phrase) — des
    bruits ou des débuts de phrase, pas des phrases entières perdues dans le bruit.
- **Corpus synthétique** (`synthetique/<voix>/`) fabriqué par `corpus.mjs` avec le fournisseur de voix déjà configuré
  (ElevenLabs `eleven_multilingual_v2`, deux voix) : phrases normales, pauses en milieu de phrase, mots courts
  (« Allons-y », « Oui »), noms de clients et sujets (CAGIP, EDENRED, agenor, data-platform, WAF), ordres d'écran,
  coupure. Cache : une phrase faite n'est pas refaite (≈ 0,04 € le corpus entier).
- `index.json` : pour chaque enregistrement, texte attendu, durée, **début et fin de parole** (blocs de 100 ms au-dessus
  de max(400, 8 % du plus fort)).

Scénarios (`phrases.json`) : conversation suivie, mots courts après une question, ordres d'écran, enchaînement rapide
(sans attendre la réponse), coupure pendant que Jarvis parle (voix renvoyée au micro), bruit de fond, les 12 vraies
captures, témoin Chrome.

## 4. La porte (objectifs chiffrés) — proposée, chiffres de référence mesurés d'abord

| Objectif | Seuil | Note |
|---|---|---|
| Phrases entendues | **≥ 95 %** | phrases à texte attendu (les captures vides sont rapportées à part) |
| Premier son après la fin de parole, médiane | **≤ 2,0 s** | aligné sur F-257 §4.1 (p90 rapporté, cible F-257 ≤ 3,0 s) |
| Réponses répétées | **0** | |
| « Voici » sans image | **0** | |
| Replis de transcription | **0** | **écart à la proposition** (« 0 repli non expliqué ») : le banc lit désormais la raison de fermeture du fournisseur, donc tout repli serait « expliqué » ; un repli reste une phrase en danger, et le 07/11 en comptait 0 |

Seuils réglables par l'environnement pour mesurer sans bloquer (`PORTE_ENTENDU_MIN`, `PORTE_PREMIER_SON_MAX_MS`).
La porte entre dans `release-checklist.md` (section Jarvis) avec SF-256-02 : **aucun déploiement de Jarvis sans banc à
vraie voix au vert**.

## 5. Ce que le banc n'est pas

- Pas un correctif : il **mesure** ; les causes et correctifs viennent ensuite (enquête en cours, F-257).
- Pas un banc de justesse du cerveau : `CONVERSATION` / `PIÈCES` / `ORAL` restent la porte de la consigne (V8).
- Pas la prod : passerelle locale, univers fictif, voix de synthèse ; les vraies captures couvrent la voix réelle du
  PO. Le banc ne lit ni n'écrit rien en prod (seulement `kubectl get` de la configuration et des clés).
- Compatible F-257 : il mesure de l'extérieur (micro, sons, appels) ; la nouvelle boucle devra garder un appel
  observable du texte compris (ou le banc l'adaptera, SF-257-01).

## 6. Découpage (chaque SF ≤ 2 j)

| SF | Titre | Contenu | Estim. |
|---|---|---|---|
| SF-256-01 | Corpus et banc à vraie voix | sauvegarde des captures (fait, hors dépôt) ; `corpus.mjs` ; passerelle locale et amorce ; micro virtuel et sondes ; mesure tour par tour, porte, résumé, chronologie ; témoin Chrome ; tests de la mesure (`node --test`) ; README | 2 j |
| SF-256-02 | Référence et porte de mise en ligne | passage de référence sur `origin/main`, chiffres consignés ici (§7) ; règle « aucun déploiement de Jarvis sans banc à vraie voix au vert » dans `release-checklist.md` ; ligne V9 dans `testing-strategy.md` | 0,5 j |

Préoccupations transversales : **aucune** (aucun code de production, aucune route, aucune table). Isolation `user_id` :
sans objet (passerelle locale, compte de test unique) ; le corpus réel est lu en lecture seule et rangé hors dépôt.

## 7. Référence de la version actuelle (passages du 2026-11-11, code `8ce0649d` = `origin/main` du 11/11 au matin)

Passerelle locale, modèle et voix de la prod (cerveau rapide `claude-sonnet-5-5`, voix OpenAI `onyx`, écoute
ElevenLabs Scribe v2 Realtime + retranscription Scribe v2 batch), 8 scénarios, 33 enregistrements (25 à texte attendu,
7 captures presque muettes, 1 témoin). Rapports : `~/.local/share/claude-gateway/banc-jarvis-voix/reference-8ce0649d-2d/`
(vue 2D, défaut) et `…/reference-8ce0649d/` (vue 3D en rendu logiciel).

| Mesure | Vue 2D (défaut) | Vue 3D (rendu logiciel) | Objectif |
|---|---|---|---|
| Phrases entendues | **24/25 (96 %)** | 23/25 (92 %) | ≥ 95 % |
| Phrases comprises (WER ≤ 0,25) | 24/25 (96 %) | **15/25 (60 %)** | — (rapporté) |
| Premier son après la fin réelle de parole, médiane / p90 | **6,1 s / 8,2 s** | 5,7 s / 14,8 s | ≤ 2,0 s (p90 ≤ 3,0 s) |
| Replis de transcription | 1 (`coupe`) | **26/27 tours** (`coupe`) | 0 |
| Fermetures de session ElevenLabs | 17 × 1005, 7 × 1006 (fermées par l'écran), **1 × `queue_overflow`** | **45/45 × `queue_overflow`** | — |
| Réponses répétées | 0 | 0 | 0 |
| « Voici » sans image | **1** (capture « montre-moi la galerie vidéo » : ordre `galerie` refusé `NOTHING`, Jarvis dit « Voilà ») | 0 | 0 |
| Ordres d'écran | 6 demandés, 5 affichés, 1 refusé | 4 / 4 / 0 | — |
| Captures vides du PO (7) | aucune ne donne de tour | idem | — |
| Coût du passage | **0,51 €** | 0,31 € | — |

**Porte : NON FRANCHIE** sur la version actuelle (premier son ; « Voici » sans image ; repli en 2D — et entendu,
compris, replis en 3D). La règle s'applique aux prochaines mises en ligne de Jarvis : les correctifs d'abord.

### Ce que la référence confirme

1. **La régression de la transcription en direct est confirmée, et sa cause est lue** : la session ElevenLabs est
   fermée par le **serveur** avec la raison **`queue_overflow`** — « *Session terminated: audio data is being sent too
   frequently. Please reduce the rate at which you send audio chunks.* » (message `queue_overflow` reçu juste avant la
   fermeture 1000). Le client ne reconnaît pas ce type de message (il voit `stream_error`, puis repli batch `coupe`), d'où
   l'absence de raison dans le journal de prod.
2. **Elle dépend de la charge du fil principal** : 45/45 sessions tuées en 3D logicielle (fil principal saturé), 1/25 en
   2D. Le **témoin** (micro factice de Chrome, sans le mélangeur du banc) donne la même fermeture en 3D : le banc
   n'invente rien. Mécanisme le plus probable : le module de capture poste **un bloc de 128 échantillons (2,7 ms) par
   message** vers le fil principal, qui l'envoie aussitôt au fournisseur ; quand le fil principal est occupé, les blocs
   arrivent **en rafale**, et la pré-écoute de 1,5 s (SF-251-02, micro gardé, 09/11) part elle aussi d'un bloc de 15
   envois à l'ouverture. À confirmer par l'enquête (piste : regrouper par ~100 ms et lisser l'envoi).
3. **Une session tuée en pleine phrase coupe la phrase** : en 3D, les vraies captures du PO sont entendues **tronquées**
   (« Est-ce que tu vois des sujets » pour « … en cours côté WAF sur EDENRED ? », « Annonce » pour « Allons sur le
   dernier chantier… ») et Jarvis répond « Pardon, Monsieur, je n'ai pas saisi » — exactement le ressenti du PO. En 2D,
   les 5 mêmes captures sont comprises mot pour mot (WER 0).
4. **Lenteur** : 6,1 s en médiane entre la fin réelle de parole et le premier son. Décomposition (mesures de l'écran,
   médianes) : **≈ 2,2 s** de silence avant que l'écran déclare la fin de tour, **0,9 s** de retranscription, **0,7 s**
   jusqu'au premier mot du cerveau rapide, **≈ 2,1 s** de voix jusqu'au premier octet audio (OpenAI `onyx`), 0,2 s de
   lecture. Aucune de ces étapes ne tient seule l'objectif de 2 s (cohérent avec F-257).
5. **« Voici » sans image** reproduit sur une vraie phrase du PO (galerie vidéo refusée, réponse « Voilà… »).
6. **Mot court traité hors sujet** : « Oui. » après « Allons-y » est pris pour l'acceptation de l'**entretien du
   portrait** (« Très bien. Dites « je passe » quand vous voulez… ») — un « mauvais sujet » reproductible.
7. **Captures vides** : les 7 captures sans texte du 09/11 ne contiennent que ≤ 0,5 s de son (bruits, débuts de mot) ;
   Jarvis ne les prend pas pour des phrases (aucune réponse) — pas de phrase perdue dans le bruit.
8. **Enchaînement rapide** : une seconde question posée 0,8 s après la première, sans attendre la réponse, est perdue
   (2D et 3D).
