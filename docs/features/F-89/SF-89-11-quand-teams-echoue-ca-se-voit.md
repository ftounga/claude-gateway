# SF-89-11 — Quand Teams échoue, ça se voit, et le repli est un choix

> Cadrage du 2026-09-15, constat du PO en production. **Cadrage seul : livraison sur go du PO (donné).**

## Constat (prod, poste CAGIP, terminal Teams 835cc2dc)

> « Sa réponse ne vient pas de Teams, et ce n'est pas la première fois. Quand il n'arrive pas à
> récupérer le message de Teams, il faut qu'on en soit notifié, qu'il propose de réessayer OU de
> passer par le contenu du projet (bash) — mais ça doit ABSOLUMENT être une solution de repli, et on
> doit voir VISUELLEMENT, même par la couleur, que Teams a échoué. »

Journal prod : `teams_find_meetings` OK puis `teams_find_conversations` OK (retour **sans résultat
utile** : NOTHING_SERVED / NOTHING_CLASSIFIED), **puis l'agent enchaîne `bash` + `edit_file`** et répond
à partir des fichiers du projet. La consigne `NOTHING_RULE` (SF-89-08) est trop **molle** : l'agent la
contourne et se rabat en silence sur le poste. Rien ne signale à l'utilisateur que la réponse ne vient
pas de Teams.

## Décision : l'échec Teams est un état bloquant et visible, le repli est explicite

1. **Un bloc d'échec dédié, coloré.** Quand un outil de lecture Teams (`teams_find_meetings`,
   `teams_find_conversations`, `teams_read_conversation`, `teams_mentions`, `teams_search`,
   `teams_meeting_transcript`) ne rend rien d'exploitable (NOTHING_SERVED, NOTHING_CLASSIFIED, liaison
   absente, session Microsoft expirée, « Teams a changé d'écran »), l'agent **s'arrête sur cet échec**
   et l'écran affiche un **bloc « Teams n'a pas pu être lu »** — un genre de bloc riche du terminal
   Teams (comme les cartes F-89), **couleur d'échec de la charte** (§12 ambre pour « attention/action
   requise », ou rouge §5 si liaison rompue), jamais un simple paragraphe. Le motif exact est écrit
   (rien servi / non reconnu / non relié / session expirée / écran changé) avec le geste qui débloque.
2. **Le repli est un CHOIX de l'utilisateur, jamais silencieux.** Le bloc propose deux actions :
   **« Réessayer »** (relance la lecture Teams) et **« Chercher dans le projet à la place »**
   (autorise l'agent à répondre via bash/les fichiers du poste). **Tant que l'utilisateur n'a pas
   choisi, l'agent NE répond PAS à la question de fond depuis le projet.** La consigne système est
   durcie en règle non négociable : sur échec Teams, produire le bloc d'échec et **s'arrêter** ; ne
   jamais substituer le contenu du projet à Teams sans le geste explicite.
3. **Toujours distinguable à l'œil.** Une réponse issue de Teams et une réponse de repli (projet/bash)
   ne se ressemblent pas : le bloc d'échec et, le cas échéant, un bandeau « réponse basée sur le
   projet, pas sur Teams » marquent la source. La couleur porte l'information (charte §11/§12), pas
   seulement le texte.

## Portée technique

- **Backend** : dans `AtelierChatService` / `TeamsToolCatalog`, transformer le « zéro » des outils de
  lecture en un **résultat d'échec typé** qui (a) émet un bloc riche d'échec dans le fil (nouveau genre
  `TeamsBlockCard.Kind.READ_FAILED` ou équivalent, display-only), (b) porte les actions Réessayer /
  Repli, (c) **empêche** la poursuite vers une réponse substantielle tant qu'aucun choix. Durcir la
  consigne (remplacer NOTHING_RULE molle par une règle bloquante).
- **Frontend** : rendu du bloc d'échec Teams avec la couleur d'échec de la charte (aucune couleur
  nouvelle), deux boutons ; le clic « Réessayer » renvoie une précision « réessaie la lecture Teams »,
  le clic « Chercher dans le projet » une précision « autorisé à répondre via le projet ».
- **La lecture d'écran (SF-89-06) reste tentée AVANT de déclarer l'échec** : l'échec n'est déclaré que
  si réseau **et** écran n'ont rien donné. (Le relevé du 2026-09-15 confirme : la transcription ne vient
  que par l'écran ; l'échec ne doit pas se déclencher tant que l'écran n'a pas été essayé.)

## Critères d'acceptation

1. Sur une lecture Teams sans résultat, l'écran affiche un **bloc coloré « Teams n'a pas pu être lu »**
   avec le motif, et l'agent **ne répond pas** la question de fond depuis le projet.
2. « Réessayer » relance la lecture Teams ; « Chercher dans le projet » autorise le repli, et la
   réponse qui suit est **marquée « basée sur le projet »**.
3. Un test prouve qu'aucun `bash`/`read_file` de repli n'est émis avant le choix de l'utilisateur.
4. La distinction Teams / repli est visible à la couleur, pas seulement au texte.

## Hors périmètre / à cadrer plus tard
- **La couleur de fond du terminal Teams jugée trop claire** par le PO (2026-09-15) : ajustement de la
  peau « Papier » (SF-89-09), **cadré et livré séparément**.
- Lire les trames socket trouter (messages temps réel) : hors périmètre.
