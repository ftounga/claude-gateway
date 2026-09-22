# Cadrage — F-147 — Un enregistrement déposé devient une réunion, tout de suite

> Demande du PO, 2026-09-22, puis deux précisions décisives :
> *« Ce mécanisme ne me plaît pas du tout… on ne sait pas quand le runner va venir prendre, et
> ensuite une synchronisation doit se déclencher. »*
> *« J'aurais voulu pouvoir y faire référence depuis mon terminal… et je ne sais pas si et comment la
> réunion rentre dans la base de connaissances. »*
> Arbitrages : **promotion proposée et rappelée**, **outil de lecture du transcript**, **sujet
> obligatoire au dépôt**.

## 0. Trois affirmations fausses, corrigées

Ce cadrage a été réécrit **trois fois** parce que j'ai conclu trois fois trop vite. C'est écrit ici
pour que la suite parte du réel, et pas de ma mémoire :

| Ce que j'avais affirmé | Le réel |
|---|---|
| « L'application ne sait pas traiter une vidéo » | **Si** — trois chemins existent (dépôt écran, dossier, téléchargement Teams) |
| « Des segments audio remonteraient vers Whisper » | **Non** — la transcription des enregistrements est **locale** (F-91) : ni vidéo ni audio ne quittent le poste |
| « Il faut construire le geste de sélection » | **Il existe** — F-104 / SF-104-04 : un dialogue envoie le fichier **par morceaux au runner**, jusqu'à 500 Mo, **sans jamais le stocker côté gateway** |
| « Il faut supprimer le dossier de dépôt » | **Non** — c'est la **zone d'arrivée** de ce transfert ; le supprimer casserait le geste que le PO veut garder |

## 1. Ce qui existe réellement

| Chemin | État |
|---|---|
| **Dépôt depuis l'écran** (dialogue Vigie, morceaux → runner, 500 Mo, `.mp4/.mov/.mkv/.webm` + audio) | **livré** (F-104 / SF-104-04) |
| **Transcription locale** (ffmpeg + modèle sur la machine, rien ne sort) | **livré** (F-91 / SF-91-03) |
| **Téléchargement d'un enregistrement Teams** par Chrome vers le poste | **livré** (F-108 / SF-108-05) |
| **Exploitation d'une réunion** : résumé, points clés, décisions, actions, Q&A | **livré** (F-128 / SF-128-05) |
| **Promotion vers la carte du poste** : faits durables rangés dans les bons fichiers | **livré** (F-128 / SF-128-11) |
| Une réunion **appartient à un sujet** (`Meeting.subjectId`) | **déjà dans le modèle** |
| **La boucle du savoir** : carte → contexte des tours suivants | **livré** (F-136) |

## 2. Ce qui cloche vraiment

Le reproche du PO est juste, mais il ne porte pas sur le geste d'entrée :

1. **Le fichier arrive, puis attend une synchronisation.** `finish` pose le fichier et son compagnon,
   et s'arrête là : c'est un relevé périodique qui le prendra, plus tard, sans qu'on sache quand.
2. **Aucune progression n'est montrée**, alors que le runner porte déjà les phases et leurs
   libellés : *« j'extrais le son »*, *« je transcris, ici, sans rien envoyer nulle part »*.
3. **Le résultat est une preuve Radar, pas une réunion** : donc pas de résumé, pas de décisions, pas
   de Q&A, et pas de promotion vers la carte.
4. **Rien ne rattache le dépôt à un sujet** au moment du geste — alors que c'est là que ça a du sens.

## 3. Ce qu'on construit

| SF | Objet | Touche le runner ? |
|---|---|---|
| **SF-147-01** | `finish` **déclenche la transcription immédiatement** et rend un identifiant de travail ; l'écran **montre la progression**, phase par phase | **oui** |
| **SF-147-02** | **Sujet obligatoire** au dépôt ; le résultat devient une **réunion** rattachée à ce sujet, exploitable | non |
| **SF-147-03** | Promotion vers la carte **proposée et rappelée** tant qu'elle n'est pas faite (régime de la dette de F-125) | non |
| **SF-147-04** | `meeting_transcript` — lire le transcript d'une réunion depuis un terminal | non |
| **SF-147-05** | Retirer la **surveillance périodique** du dossier — devenue inutile une fois SF-147-01 en place | oui (retrait) |
| **SF-147-06** | **Rattraper** un texte resté sur un poste qui était hors ligne au moment de le déposer (ajoutée le 2026-09-22, en écrivant SF-147-02 : la remontée peut échouer, et une réunion « en attente » ne doit pas le rester pour toujours) | oui |

**Le dossier n'est pas supprimé** : il reste la zone où le fichier atterrit le temps d'être traité.
Ce qui disparaît, c'est la **boîte aux lettres** — le relevé automatique sans geste ni progression.

**Ordre** : SF-147-01 et 02 d'abord (l'irritation), puis 03 et 04, puis 05 (retrait, une fois que
plus rien n'en dépend).

## 4. Hors scope
- Les **images clés** d'un enregistrement déposé (deck reconstitué) — à décider séparément.
- La **diarisation** : le moteur local ne sépare pas les voix, et une attribution fausse est pire
  qu'une absence d'attribution (règle de F-91).
- Toute remontée d'audio ou de vidéo vers un service tiers : **exclue** (décision de F-91).
