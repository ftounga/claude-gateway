# Cadrage F-212 — Les captures de preuve

**Demande PO du 2026-10-24** (dans la conversation Claude Code) : « À la fin d'un sujet clôturé, je te demande un message pour dire que c'est fini, et derrière je veux des captures d'écran qui montrent que ça s'est bien passé […] que tu exécutes des commandes dans le terminal et que tu prennes des captures des réponses […] s'il y a plusieurs commandes, le plus judicieux serait de les exécuter dans le même terminal à la suite, puis prendre les captures. Regarde la faisabilité. »

## Faisabilité (étude du 2026-10-24) — **faisable**
Choix structurant : **une capture fidèle rendue, pas une photo de l'écran du poste.** Prendre l'écran Windows du poste exigerait une session graphique visible, capterait tout ce qui est ouvert (fuite), et ne prouverait pas mieux. On produit à la place une **image style terminal** générée à partir de la **sortie brute renvoyée par le poste** (jamais du texte écrit par le modèle), signée du poste, du shell, du dossier, de l'heure, du code de sortie et de la durée.

Existant réutilisable : rendu headless chromium du service `diagram-renderer` (`shoot()`, puppeteer pour le PDF F-184) ; stockage `WorkspaceStorage` + patron `TeamsMomentImageService` / `TeamsMomentController` (seule image déjà affichée dans un fil) ; capacités annoncées par le runner (patron F-190 `video`) et mise à jour du runner en un clic.

Manques : (a) le runner lance **un processus par commande** (`BashTool` l.286) — pas de cwd/env partagés ; (b) la sortie stockée est **fusionnée et tronquée** (131 Ko puis 4 000 caractères) ; (c) un `Block` textuel **interdit une carte** dans un terminal de projet.

## Décisions
1. **Outil agent `capturer_preuves(titre, commandes[1..8])`** (cible poste uniquement ; terminal hébergé : refus explicite). Les commandes sont **soumises à la même porte que bash** (classement lecture / invite d'autorisation / porte du parcours) : une commande non-lecture demande l'accord comme aujourd'hui.
2. **Runner : outil `proof_run`** (capacité `proof`) : exécute les commandes **à la suite dans UN MÊME shell** (script unique avec séparateurs : cwd et variables conservés d'une commande à l'autre), relève par commande : stdout/stderr distincts, code de sortie, durée, cwd, horodatage ; borne 64 Kio par commande (tête + fin, troncature affichée). Runner non à jour ⇒ message « mettez à jour le poste ».
3. **Rendu** : route `diagram-renderer` `/terminal-shot` (HTML terminal → PNG via chromium) : invite `user@poste cwd $`, commande, sortie (couleurs ANSI de base), pied « code 0 · 1,2 s · 24/10 17:42 · poste CAGIP · PowerShell ». Une image par commande + une planche récapitulative.
4. **Stockage** : S3 `preuves/{userId}/{workspaceId}/{id}.png` (types fermés), table `proof_captures` (migration **168** : id, user_id, workspace_id, message_id, title, position, command, exit_code, duration_ms, cwd, shell, host, captured_at, storage_key) ; route `GET /workspaces/{id}/proofs/{captureId}` (isolation `user_id`) ; effacement avec le terminal.
5. **Dans le fil** : nouveau bloc riche `proof` du compte rendu de tour (constructeur dédié, rétro-compatible) : vignettes, zoom, **« Copier l'image »** (presse-papiers image, pour coller dans Teams ou un mail), « Télécharger », mention « Capture générée à partir de la sortie réelle du poste ». L'agent reçoit le texte des sorties (pour conclure) et peut s'y référer dans son message de clôture.
6. Consigne : quand l'utilisateur demande une preuve (« montre que ça marche », message de fin de sujet), utiliser `capturer_preuves` avec les commandes de vérification, à la suite, plutôt que bash seul.

## Amendement PO du 2026-10-24 (prioritaire sur les décisions ci-dessus)
1. **Usage élargi** : la preuve sert dès que l'agent affirme qu'un point est validé (y compris en plein chantier : étape `update_plan_step` VERIFIE, « c'est corrigé »), quand l'utilisateur demande des preuves ou que l'affirmation porte sur un état vérifiable du poste. Paramètre facultatif `etape` sur `capturer_preuves` : la preuve de l'étape mentionne la capture.
2. **Réalisme total de l'image** : rien dans le PNG ne trahit qu'il est généré — **pas de pied** (code, durée, heure, poste, shell) ni de mention « générée » dans l'image ; ces métadonnées restent dans l'interface, sous la vignette. Invite de la vraie machine (vrai compte, nom d'hôte court, dossier courant avec `~`). Le runner relève `os`, `user`, `host`, `home`, `interactiveShell` et exécute la suite sous le `$SHELL` de l'utilisateur (`-c`, non login) quand il parle POSIX, sinon le shell élu ; un seul processus. Style fidèle par plateforme : Linux GNOME Terminal/bash (`user@host:~/chemin$`), macOS Terminal.app zsh (`user@Host chemin %`, profil Basic clair, trois pastilles) ou bash (`Host:chemin user$`), Windows Terminal PowerShell (`PS C:\…>`, Campbell). Polices libres seulement, embarquées dans diagram-renderer. **Une image par fenêtre/session** (toutes les commandes à la suite, découpée seulement si trop haute) au lieu d'une image par commande. Invite personnalisée par poste : facultatif, reste à faire si hors délai.
3. **Coût** : les images ne retournent jamais au modèle ; résultat d'outil = texte court (≈ 2 000 caractères par commande tête + fin, ≈ 6 000 au total, identifiants des captures) ; définition d'outil + consigne courtes et fixes (< 250 tokens) ; pas de preuve systématique ; pas de commande lancée en bash « pour voir » juste avant la capture.
4. **Usage cible** : les captures sont jointes à l'encart message/mail F-203 qu'elles prouvent (attribut `preuves=<id1>,<id2>` dans la balise, à côté de `attente=` de F-211 ; repli : les captures du tour sous le dernier encart) ; boutons « Copier l'image » par capture et « Copier le message avec les captures » (text/html avec images en data: + text/plain), « Télécharger » en repli ; le bouton Copier existant et « Marquer envoyé » restent intacts. Les captures restent visibles dans leur bloc preuve du fil.
5. Garantie inchangée : le contenu vient toujours de la sortie brute du poste ; porte et invite inchangées ; filtre de secrets avant rendu.

## Préoccupations transversales
- **Contexte tenant** : table + route d'image ⇒ `user_id`, `requireOwned`, test 404.
- **Sécurité du poste** : aucune commande ne contourne la porte ni l'invite (test dédié) ; filtre de secrets appliqué à la sortie avant rendu (comme la sortie bash).

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-212-01 | Runner : `proof_run` (même shell, séparateurs, relevés par commande, bornes), capacité `proof`, contrat, tests | 🟠 |
| SF-212-02 | diagram-renderer : `/terminal-shot` (HTML terminal → PNG, ANSI, pied) + client backend ; tests | 🟢 |
| SF-212-03 | Backend : outil `capturer_preuves`, porte/invite par commande, filtre de secrets, rendu + S3 + migration 168 + route image, bloc `proof` dans le compte rendu, consigne ; tests (isolation, porte) | 🟠 |
| SF-212-04 | Front : bloc preuve (vignettes, zoom, Copier l'image, Télécharger), relecture de l'historique | 🟢 |

## Hors périmètre
Capture graphique de l'écran du poste ou d'une application ; terminal hébergé (sandbox) — extension possible plus tard.
