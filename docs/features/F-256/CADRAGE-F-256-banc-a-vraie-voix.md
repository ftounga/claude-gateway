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

## 7. Référence de la version actuelle

Voir SF-256-02 (renseigné après le passage de référence).
