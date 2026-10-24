# Mini-spec — F-212 / SF-212-02 Rendu : `/terminal-shot` (une fenêtre de terminal fidèle)

## Identifiant
`F-212 / SF-212-02` — Feature parente : F-212 « Les captures de preuve » — Statut : `in-review` — 2026-10-24
Branche : `feat/SF-212-02-rendu-terminal`

## Objectif
Le service `diagram-renderer` transforme le relevé brut d'une session de preuve (SF-212-01) en image(s) PNG d'une **vraie fenêtre de terminal** de la plateforme du poste, sans rien qui trahisse la génération (amendement PO du 2026-10-24) ; la gateway l'appelle par un client dédié.

## Comportement attendu
### Cas nominal
- `POST /terminal-shot` (relevé JSON du runner : `os, user, host, home, execShell, cwd, commands[]`) → `200 {kind, cols, images:[{png(base64), width, height}]}`.
- **Une fenêtre par session** : invite (dossier AVANT la commande), commande, sortie standard puis sortie d'erreur, invite suivante… et invite finale avec curseur. Découpée en plusieurs fenêtres seulement au-delà de 50 lignes.
- Styles fidèles aux défauts : Linux = GNOME Terminal / bash Ubuntu (`user@host:~/chemin$`, vert et bleu gras, fond aubergine, Ubuntu Mono, barre GNOME `user@host: ~/chemin`) ; macOS zsh = Terminal.app Basic (`user@Host dossier %`, fond clair, DejaVu Sans Mono ≈ Menlo, trois pastilles, titre `dossier — -zsh — 80×N`) ; macOS bash (`Host:dossier user$`) ; Windows = Windows Terminal Campbell, Cascadia Mono, onglet « Windows PowerShell » (`PS C:\…> `), cmd (`C:\…>`, ligne vide avant l'invite), Git Bash (`user@HOST MINGW64 ~/chemin` puis `$ `).
- **Aucun pied** (code, durée, heure, poste, shell) ni mention « générée » dans l'image : ces métadonnées sont pour l'interface (SF-212-04).
- Largeur 80 colonnes (défaut des trois terminaux), jusqu'à 120 si une ligne l'exige ; retour à la ligne à la colonne comme un vrai terminal ; couleurs ANSI réelles (16, 256, 24 bits ; gras = couleur vive, réglage par défaut), `\r` réécrit la ligne, séquences non graphiques ignorées.
- Hors ligne : chromium refuse toute requête ; polices libres embarquées dans l'image (Ubuntu Mono Nerd Font, Cascadia Mono Nerd Font = « CaskaydiaCove », DejaVu Sans Mono) — seules les variantes Mono Regular/Bold sont gardées.
- Échelle ×2 (net une fois collé dans Teams), ombre de fenêtre, fond transparent.
- Client gateway `HttpTerminalShotRenderer` (même `app.diagrams.base-url`, délai ≥ 45 s, 12 images au plus, chaque image bornée par `max-image-bytes`).

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| corps non JSON | `Corps JSON invalide.` | 400 |
| 0 ou > 8 commandes, commande sans texte | raison explicite | 400 |
| corps > 2 Mo | `Relevé trop volumineux` | 413 |
| chromium > 30 s | `Capture trop longue` | 504 |
| côté gateway : 400/413/422 | `DiagramRejectedException` (raison du service) | — |
| côté gateway : service muet / non configuré | `DiagramRendererUnavailableException` | — |

## Critères d'acceptation
- [x] Invite et titre exacts par plateforme (Linux, macOS zsh/bash, PowerShell, Git Bash).
- [x] Pas de pied ni de mention dans la page.
- [x] Une fenêtre par session, découpe au-delà de 50 lignes.
- [x] Largeur 80→120, retour à la ligne, `\r`, couleurs 16/256/24 bits, gras vif.
- [x] Commande interrompue : pas d'invite finale ; commandes non exécutées absentes.
- [x] Texte échappé (aucune injection HTML).
- [x] Image construite et vérifiée à l'œil (trois plateformes) sur le conteneur.

## Plan de test
`node --test diagram-renderer/tests/test_terminal.js` (12 cas) ; `HttpTerminalShotRendererTest` (4 cas, serveur HTTP local) ; rendu réel vérifié sur l'image Docker construite. Isolation `user_id` : sans objet (service sans état, aucune donnée stockée).

## Préoccupations transversales
Aucune (Auth, tenant, plans, navigation : non). Service sans état ; NetworkPolicy inchangée (aucune requête sortante).

## Impacts
`diagram-renderer/terminal.js` (nouveau), `server.js` (route), `Dockerfile` (polices, copie), `backend/…/atelier/proof/TerminalShotRenderer` + `HttpTerminalShotRenderer` (nouveaux). Aucune configuration nouvelle.

## Hors périmètre
Appel par l'outil agent, stockage, porte (SF-212-03) ; affichage (SF-212-04) ; invite personnalisée par poste (reste à faire) ; planche récapitulative (rendue inutile par « une image par session »).
