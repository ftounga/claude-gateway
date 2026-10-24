# Cadrage F-214 — Les retours à la seconde

**Demande PO du 2026-10-24** (même conversation que F-213) : reprise de la pièce maîtresse de la vidéo étudiée (« Editing OS ») — mettre la vidéo en pause, écrire « la bannière à droite n'est pas visible, je la veux en noir », la remarque reste **attachée à 00:10 / image 304** ; quand tous les retours sont posés, **« Envoyer à l'agent »** produit la version suivante. Validé par le PO : « pour corriger, vous mettrez la vidéo en pause au bon moment et écrirez votre remarque […] un clic sur Envoyer à l'agent ».

**Ce que F-214 apporte** : corriger une vidéo **comme on parle à un monteur**, précisément, sans décrire le moment en mots ; et vérifier, version après version, que chaque retour a été traité.

## Existant réutilisable
- **F-213** : vidéos versionnées (`video_versions`), lecteur dans le fil et dans la galerie.
- **Saisie du terminal** : une action peut **remplir la saisie sans envoyer** (patron Cap F-192, Plume F-193) — l'utilisateur garde la main.
- **Outils de lecture de l'agent** : patron des outils serveur (lecture seule, isolation) ; F-190 `video_images` permet à l'agent de **regarder** l'instant commenté.

## Décisions proposées
1. **Poser un retour** : dans le lecteur (fil et galerie), touche ou bouton « Commenter cet instant » ⇒ la vidéo se met en pause, le champ affiche `00:10 · image 304` (image = arrondi à la cadence de la vidéo, 30 i/s par défaut, lue dans les métadonnées de version), Entrée enregistre. Facultatif : **plage** (début–fin) pour « de 01:20 à 01:35 c'est trop rapide ». Marqueurs sur la barre de progression ; clic sur un retour ⇒ la vidéo saute à l'instant.
2. **Modèle** : table `video_comments` (id, user_id, video_id, version, t_start_ms, t_end_ms null, frame, text, status `OUVERT | ENVOYE | TRAITE | REJETE`, sent_at, created_at). Isolation `user_id`. Migration au dev (max + 1).
3. **Envoyer à l'agent** : compose dans la saisie du terminal du sujet (sans envoyer) un message structuré :
   `Retours sur « <titre> » v<n> (<id>) :` puis une ligne par retour `00:10 (image 304) — la bannière à droite n'est pas visible, je la veux en noir`. Variante **« Copier »** (presse-papiers). Les retours passent `ENVOYE`.
4. **Outil agent `video_retours(video, version?)`** (lecture seule) : liste des retours avec horodatage, image, texte, statut — pour que l'agent ne dépende pas du texte recopié et puisse **regarder chaque instant** (`video_images` au temps donné) avant de corriger.
5. **Version suivante** : l'agent republie avec `video_publier(remplace: <id>)` (F-213) et indique pour chaque retour `traite` ou `rejete` + motif (paramètre `retours_traites[]`) ⇒ statut mis à jour, visible sous le lecteur ; la galerie montre la **dernière version**, l'historique des versions reste consultable, et un sélecteur v1/v2 permet de **revoir le même instant** dans les deux versions.
6. **Série (F-213)** : les retours sont par épisode ; « Envoyer à l'agent » ne concerne que l'épisode affiché (corriger le chapitre 6 sans toucher aux autres — exigence du format long F-215).

## Préoccupations transversales
- **Contexte tenant** : routes `video_comments` et outil `video_retours` filtrés `user_id` + propriété de la vidéo ; tests 404 croisés.
- Aucune préoccupation auth / plans / routing (pas de nouvelle route d'écran : le lecteur existe via F-213).

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-214-01 | Backend : `video_comments` (CRUD, statuts), outil `video_retours`, `retours_traites` sur `video_publier(remplace)` ; tests (isolation, transitions de statut) | 🟢 |
| SF-214-02 | Front : commenter un instant / une plage, marqueurs, liste, « Envoyer à l'agent » (remplit la saisie) et « Copier », statuts par version, comparaison v1/v2 au même instant | 🟠 |

## Hors périmètre
Dessin sur l'image (annotation graphique) ; retours à plusieurs personnes / validation client ; envoi automatique sans geste de l'utilisateur.
