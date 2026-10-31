# SF-228-01 — Le contenu d'une pièce jointe, pour son aperçu (backend)

**Feature :** F-228 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-228-01-contenu-des-pieces`

## Objectif
L'écran peut demander les octets d'une pièce posée dans le fil, et seulement celle-là, pour l'afficher.

## Comportement
1. `GET /api/workspaces/{id}/deposits/{depositId}/content` : ne sert **que** une pièce inscrite dans `atelier_deposited_files` du couple (`user_id`, terminal) — jamais un chemin libre ; lue par `ProjectFileRead` (poste par tranches, ou hébergé), plafond **20 Mo**.
2. Type par l'extension (images, PDF, vidéo, audio, SVG, CSV, texte, JSON, tableurs, docx, zip) ; tout inconnu et tout **HTML** → `application/octet-stream` (jamais servi comme page). En-têtes : `Content-Disposition: inline` (nom du fichier), `X-Content-Type-Options: nosniff`, `Content-Security-Policy: sandbox; default-src 'none'…` (un SVG ou un PDF ouvert seul n'a aucun droit), `Cache-Control: no-store`.
3. L'historique (`GET …/chat`) porte désormais l'**identifiant** de chaque pièce jointe (`AtelierAttachedFile.id`), pour que la puce sache quoi demander.
4. Provider-First et F-04 : aucun OCR, aucune indexation ; les octets sont rendus tels quels.

## Erreurs
| Situation | Comportement | HTTP |
|---|---|---|
| Terminal d'autrui, pièce inconnue ou d'un autre terminal | introuvable | 404 |
| Poste hors ligne / pièce disparue | « Le poste ne répond pas, ou la pièce n'y est plus : … » | 503 |
| Plus de 20 Mo | « Pièce trop lourde pour l'aperçu… ouvrez-la sur le poste. » | 503 |

## Critères d'acceptation
- [x] Une capture déposée revient à l'identique, `image/png`, `nosniff`, CSP `sandbox`, `inline`.
- [x] Un HTML revient en `application/octet-stream`.
- [x] Pièce inconnue → 404 ; Bob → 404.
- [x] Types par extension, insensibles à la casse.

## Plan de test
`DepositContentApiIntegrationTest` (3 tests, dépôt réel en hébergé). Le chemin poste réutilise `ProjectFileRead` (couvert par ses tests). Suite backend complète.

## Impact
`deposit/DepositContentService`, `DepositContentController` (+ traduction des erreurs du paquet), `DepositNotFoundException`, `DepositUnreadableException`, `AtelierDepositedFileRepository.findByIdAndUserIdAndWorkspaceId`, `AtelierAttachedFile` (+`id`). Aucune migration. Préoccupations transversales : aucune.

## Hors périmètre
Les aperçus à l'écran (SF-228-02) ; les tableurs (SF-228-03) ; docx.
