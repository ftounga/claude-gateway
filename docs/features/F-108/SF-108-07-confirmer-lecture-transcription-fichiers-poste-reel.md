# SF-108-07 — Confirmer et recaler la lecture transcription + fichiers SharePoint sur poste réel

> Cadrage du 2026-09-15 (PO). **Cadrage seul, dev « tout à l'heure ».** Sous-feature de F-108.

## Objectif (une phrase)
Confirmer sur un poste réel (CAGIP) — et recaler si la forme diffère — la lecture de la **transcription**
et des **fichiers SharePoint/OneDrive** (SF-108-03/05/06 ont été écrites « sur documentation, à confirmer
sur poste réel »), en s'appuyant sur un relevé « forme » des réponses SharePoint, comme SF-89-12/13 l'a
fait pour les réunions.

## Contexte
- La chaîne existe déjà : **SF-108-05** (`teams_meeting_recording` : transcription native Teams → `.vtt`
  voisin → transcription locale F-91), **SF-108-06** (`teams_read_docx` : texte d'un `.docx`), et
  **SF-89-13** localise le fichier par le réseau (`driveId`/`driveItemId` du récapitulatif).
- Mais les adaptateurs SharePoint/OneDrive et la forme des réponses de téléchargement **n'ont jamais été
  validés sur un vrai poste** — mention récurrente « éprouvé sur documentation, à confirmer sur poste réel »
  (SF-108-03/04/05/06). Le relevé du 2026-09-15 montre que les chemins SharePoint (`/_api/v2.1/drives/{id}/items/{id}[/content]`,
  `graph .../items/{id}/preview`, `streamembed.aspx`) restent classés **UNKNOWN** (corps non lus).

## Comportement attendu
1. À partir du récap (driveId/driveItemId, SF-89-13), la chaîne existante localise → télécharge (Chrome,
   adresse non signée) → lit le texte de la transcription (`.vtt`/`.docx`). Ce chemin est **exercé et
   confirmé** sur CAGIP.
2. Si la **forme réelle** des réponses SharePoint/Graph diffère des formes fabriquées sur documentation
   (localisation du drive item, adresse de téléchargement du `.vtt`/`.docx`, métadonnées), **recaler** les
   adaptateurs sur la vraie forme (méthode SF-89-13 : relevé « forme » des corps SharePoint → mapping).
3. **Droits** : suppose les droits d'accès de l'utilisateur (policy Teams `whoCanAccessTranscriptAndRecording`
   + permissions SharePoint). Aucun privilège ajouté, aucun secret rapatrié, adresse signée jamais dans
   notre code (Chrome télécharge). Blocage organisateur/tenant → **manque nommé**, jamais un contournement.

## Cas d'erreur
- Transcription non disponible / non partagée / policy restrictive → message « accès refusé / non
  disponible », pas un silence.
- Forme SharePoint divergente → zéro élément + manque nommé (jamais inventer), et la sonde de santé le dit.

## Critères d'acceptation
- Sur CAGIP, pour une réunion dont l'utilisateur a les droits : la transcription est **lue par le fichier**
  (source réseau/fichier, pas lecture-écran), texte non vide.
- Une réunion sans droit de transcription → échec **nommé**.
- Aucun jeton/cookie/adresse signée ne transite par notre code (test de garde conservé).

## Plan de test
- Unitaires : adaptateurs SharePoint recalés sur fixtures reconstruites du relevé « forme » (noms/types
  réels, valeurs synthétiques) ; garde « rien recopié en aveugle ».
- Poste réel (manuel) : relevé « forme » des réponses SharePoint sur CAGIP, puis lecture transcription
  bout-en-bout dans le terminal Teams.

## Groupement avec SF-89-15 (décision PO 2026-09-16)
Livrée **conjointement avec SF-89-15**, à partir du **même** relevé « forme » complet du catalogue
(SF-89-14) : une seule capture sur CAGIP exerce à la fois les surfaces Teams (calendrier liste, réunion,
récap, conversations/messages) **et** l'étape transcription/fichiers SharePoint. On recale donc en un
bloc : SF-89-15 = les 6 familles Teams ; SF-108-07 = fichiers + transcription SharePoint.

## Prérequis
Un relevé « forme » (SF-89-12/14) exerçant l'étape **transcription/fichiers** sur CAGIP (drives/items,
téléchargement `.vtt`/`.docx`) — **le même** que celui qui alimente SF-89-15.

## Hors périmètre
- Lire une transcription affichée par l'écran (SF-89-06, repli fragile, conservé).
- Nouveaux gestes d'écriture (déjà couverts SF-108-04/06).
