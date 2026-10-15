# Cadrage F-201 — Médias collés : noms uniques et marqueurs dans le texte

**Demande PO (2026-10-15)**, livraison autonome (vague). Constat vérifié sur `origin/main` 6e16f2c8 et en prod. Justesse avant coût ; charte `DESIGN_SYSTEM.md` (aucune couleur nouvelle) ; préfixe système stable (rien de volatil).

## Besoin
1. Les captures collées sont toutes nommées `image.png` et chaque collage **écrase** le précédent. Prod (30 j) : **84 dépôts** sous `.atelier/entrees/image.png`.
2. Comme Claude Code : un marqueur `[Image #1]` (vidéo : `[Vidéo #1]`, fichier : `[Fichier #1]`) inséré **à la position du curseur** dans la saisie, pour savoir où le média se situe dans le texte ; on continue à taper autour.

## Cause (code)
Navigateur ⇒ `file.name = "image.png"` ; `atelier.service.ts:234` l'envoie tel quel ; `WorkspaceDepositService.java:88-95` assainit seulement ; hébergé `storage.putFile` écrase (`WorkspaceService.java:518`), poste `.atelier/entrees/<nom>` écrit en `TRUNCATE_EXISTING`. L'agent reçoit deux fois le même chemin et lit deux fois la 2e image. Les puces s'affichent au-dessus du champ ; les longs textes collés (F-146) ont déjà un marqueur `[texte collé #N]` mais ajouté **en fin** de brouillon (`.ts:1473`) ; bug annexe `index = pastes().length + 1` (`.ts:1470`) ⇒ numéros en double après un retrait.

## Décisions
- **Nom unique côté serveur** (source de vérité) : jamais d'écrasement ; si le nom existe déjà ou est générique (`image.png`, `image (N).png`), nom horodaté `capture-AAAAMMJJ-HHMMSS[-N].png` ; suffixe `-2`, `-3` pour tout autre doublon. Le front renomme aussi les noms génériques.
- **Marqueurs inline** à `selectionStart`, numérotation monotone par brouillon ; supprimer le marqueur du texte retire la pièce ; retirer la puce retire le marqueur ; à l'envoi, le marqueur est traduit pour l'agent en référence explicite au chemin déposé (« [Image #1] = .atelier/entrees/capture-…png ») à l'endroit exact du texte.
- Même mécanisme pour `[texte collé #N]` (insertion au curseur, numérotation corrigée).

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-201-01 | Backend : noms uniques au dépôt (hébergé + poste), plus jamais d'écrasement, tests | 🟢 |
| SF-201-02 | Front : marqueurs `[Image #N]` au curseur, cycle de vie marqueur↔pièce, traduction à l'envoi, correctif numérotation | 🟠 |
