# Mini-spec — [F-190 / SF-190-05] Le suivi à l'écran

## Identifiant

`F-190 / SF-190-05`

## Feature parente

`F-190` — Regarder une vidéo (cadrage `CADRAGE-F-190-regarder-une-video.md`)

## Statut

`in-progress`

## Date de création

2026-10-11

## Branche Git

`feat/SF-190-05-le-suivi-a-lecran`

---

## Objectif

Dans le terminal, l'utilisateur voit en clair ce que fait l'agent avec la vidéo : quelle extraction il lance, sur quel fichier, dans quel mode, et quand il suit le travail.

---

## Comportement attendu

### Cas nominal

1. **Étape `video`**, côté gateway (`stepFor`) :
   - `video_images` affiche « extraction des plans · demo.mp4 », ou « zoom 00:12:30 → 00:14:00, toutes les 5 s · demo.mp4 », avec la mention « + son » si une transcription est donnée ou demandée ;
   - `video_images_status` affiche « suivi de l'extraction ».
2. **Terminal** (`chat-steps.ts`) : le type `video` s'affiche « vidéo · <détail> ».
3. Le résultat de l'outil (déjà affiché comme sortie de l'étape) porte les chemins des planches et des images. L'utilisateur peut les ouvrir sur son poste. Les lectures de planches par l'agent apparaissent comme des étapes « lecture … planche-01.jpg ».

### Écart au cadrage (assumé)

Le cadrage prévoyait des **planches visibles dans le terminal**. Or le terminal n'affiche que du texte (`AtelierTerminalBlock.output`). Montrer les images exigerait de les **remonter** du poste vers la gateway, comme F-90 pour Teams : une nouvelle route, un nouveau stockage, et un écart à « rien ne quitte le poste sauf ce que l'agent ouvre ». C'est hors de la portée d'une SF ; ce sera proposé au PO comme suite optionnelle.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `path` absent | Étape « extraction · (vidéo non précisée) » ; l'outil refuse ensuite, comme avant |
| Mode inconnu | L'étape reprend le mode tel quel ; l'outil refuse ensuite |

---

## Critères d'acceptation

- [ ] `video_images` produit une étape `video` portant le mode et le nom du fichier, plus le son s'il y en a.
- [ ] `video_images_status` produit l'étape « suivi de l'extraction ».
- [ ] Le terminal affiche « vidéo · … » pour ce type.

---

## Plan de test

- Backend : `AtelierChatServiceVideoToolsTest`, avec les étapes émises pour les deux outils (plans, zoom et son).
- Frontend : `chat-steps.spec.ts`, type `video` affiché « vidéo · … ».
- Isolation : sans objet.

---

## Composants impactés

- Backend : `AtelierChatService.stepFor`.
- Frontend : `atelier/terminal/chat-steps.ts`.

## Préoccupations transversales

Aucune (pas de route, pas de guard, pas de navigation).

---

## Périmètre

### Hors scope (explicite)

- Afficher les images ou les planches dans le terminal (voir « Écart au cadrage »).
