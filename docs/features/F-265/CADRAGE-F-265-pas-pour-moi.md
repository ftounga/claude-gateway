# Cadrage F-265 — Pas pour moi : Jarvis ne répond plus à une autre personne

**Statut :** cadrage du 2026-11-15 · famille « Jarvis » (F-239 porte de parole réelle, F-251 « Pardon », F-256 banc à
vraie voix, F-257 converse en flux) · frontend (+ une valeur de liste fermée côté passerelle).

## 1. Constat (prod, lecture seule)

Séance du PO du 2026-11-15, ancienne boucle (`CONV_STREAM`, `live=1`) :

| Heure (Paris) | Direct (`stream_text`) | Finale (`final_text`) | Voix au micro | Crête | Réponse de Jarvis |
|---|---|---|---|---|---|
| 09:34:37 | — | (PO) « Est-ce que tu peux me montrer la galerie des vidéos ? » | 4,1 s | 1,00 | « Voici la galerie des vidéos, Monsieur. » |
| 09:34:44 | « Lui-même ? » | « Maman ? Maman ? » | 0,8 s | 0,22 | « Pardon, Monsieur, je n'ai pas bien compris : pouvez-vous répéter? » |
| 09:34:55 | (rien) | « Ah bah, c'est pas dit non » | 1,1 s | 0,17 | « Pardon, Monsieur, je n'ai pas bien saisi : à quoi faites-vous référence? » |

Sources : `jarvis_listen_traces` (tours `73988b09`, `fb408f10`), `jarvis_audio_captures` (`203e992b`, `29fdc4d1`),
`atelier_messages via='JARVIS'`. Les « pardon » viennent du **cerveau rapide** (règle « phrase incompréhensible :
demande de répéter »), pas du « Pardon, Monsieur ? » de l'écran.

**Cause prouvée** : `jarvis-speech-gate.ts` ne rejette que vide, bruit, hallucinations connues et débits impossibles ;
rien ne juge si la phrase s'adresse à Jarvis. Indice inutilisé : la transcription en direct et la finale sont deux
moteurs indépendants ; sur la voix du PO (proche), elles concordent toujours (28 paires du 09 au 15/11) ; sur la voix
lointaine, la première a deviné « Lui-même ? », la seconde rien.

## 2. Décisions

- **D1 — discordance** : une phrase finale **courte** (≤ 7 mots) dont le direct (non vide, ≤ 8 mots) ne partage aucun
  mot et presque aucune paire de lettres (Dice < 0,25) est refusée `discordant` par la porte : aucun tour, et **pas**
  de « Pardon, Monsieur ? ». Direct vide : rien n'est jugé (le flux manque parfois un mot bref que la finale rattrape).
  Ne s'applique qu'à l'ancienne boucle (seule à avoir deux transcriptions indépendantes).
- **D2 — se taire plutôt que « je n'ai pas compris »** : sur une phrase courte qui ne nomme ni Jarvis, ni un sujet,
  ni un client, sans mot du travail (chantier, galerie, montre…), hors réveil
  (« Jarvis… ») et hors d'une question que Jarvis vient de poser (sa dernière phrase finit par « ? » et n'est pas
  elle-même une demande de répéter), si le cerveau répond **d'abord** qu'il n'a pas compris, toute la réponse est tue.
  Une phrase comprise reçoit sa réponse comme avant. Les deux boucles (ancienne et F-257) passent par ce chemin.
- **D3 — contrainte dure** : une **réponse brève** (tous ses mots dans le vocabulaire oui/non/stop/choix/relance, ≤ 5
  mots : « Oui. », « Non merci », « La deuxième », « Stop », « Vas-y ») n'est jamais refusée par D1 ni tue par D2.
  Une confirmation en attente (`pendingConfirm`, accord oral) contourne déjà la porte : inchangé.
- **D4 — mesure** : `rejected_kind` reçoit `discordant` (liste fermée, sans texte) ; le silence de D2 est tracé
  `pardon_hushed` et compté en fausse alerte (`falseTriggers`).
- **Écarté** : juger sur le niveau (crête 0,2 contre ≥ 0,7) — dépend du micro et du gain (F-241 / SF-241-06) ; à
  mesurer avant d'en faire une règle. Toucher la consigne du cerveau — exigerait les bancs V8 pour un gain que le
  filtre déterministe obtient sans risque sur la justesse.

## 3. Validation

Tests unitaires sur les cas réels ; banc à vraie voix F-256 (2 passages, boucle en flux comme la référence
`sf257-12-voix-1/-2`, + l'ancienne boucle active chez le PO) avec un nouveau scénario **voix tierce** : une autre voix
de synthèse, atténuée, et les deux vraies captures du 15/11. Porte : « nettement meilleur, sans régression » — entendu
et compris non dégradés (mots courts surtout), pardons parasites en baisse.

## 4. Découpage

| SF | Titre | Estim. |
|---|---|---|
| SF-265-01 | Pas pour moi : discordance direct/finale et silence sur « je n'ai pas compris » | 1 j |
