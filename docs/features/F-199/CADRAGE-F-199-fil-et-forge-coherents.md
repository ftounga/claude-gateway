# Cadrage F-199 — Fil : ouvrir la tâche d'un autre client garde la Forge cohérente

**Demande PO (2026-10-15)**, livraison autonome (vague). Constat vérifié sur `origin/main` 6e16f2c8 et en prod. Justesse avant coût ; charte `DESIGN_SYSTEM.md` (aucune couleur nouvelle) ; préfixe système stable (rien de volatil).

## Besoin
Depuis la bande « Fil » (F-191), cliquer sur une tâche d'un autre client ouvre bien son terminal, mais la Forge reste sur l'ancien client : situation incohérente.

## Constat (code)
- `terminal-fil.component.ts:355-365` : `router.navigate(['/atelier', task.workspaceId])` seul, sans client, sans garde de tour (alors qu'`openHandoff` refuse de naviguer pendant un tour, l.880-883).
- La Forge (`/forge/:hostRef`, `postes.component.ts:449-475`) tire le client de l'URL, sinon `defaultHostRef` ; le lien « Forge » du shell (`shared/space-links.ts:14-29`) lit le client dans l'URL courante — sur `/atelier/…` il n'y en a pas ⇒ `/forge` ⇒ poste par défaut. Aucun service ne retient le client courant.
- Le backend limite le Fil au poste du terminal (`PosteTurnsService.java:59-72`), sauf les terminaux sans poste (Hébergé). **À vérifier en prod** (sujets/postes réels du PO) ce que recouvre « autre client » : autre poste visible via un cas non couvert, Hébergé, terminal Teams. L'agent le mesure avant de coder et le documente dans la PR.

## Décisions
- Un « client courant » unique dérivé du terminal ouvert (hôte du workspace actif), exposé par un service ; le lien Forge, le fil d'Ariane et la Forge le suivent. Ouvrir un terminal d'un autre client **bascule** le contexte client partout.
- Le clic du Fil passe par le même chemin que les autres navigations (garde de tour : pendant un tour, le tour continue en arrière-plan — F-84 — et la navigation est permise, mais l'état d'écran est remis à zéro via F-198).
- La bande indique le client de chaque tâche quand il diffère du terminal courant.
- Dépend de F-198 (remise à zéro de l'état de tour).

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-199-01 | Mesure en prod de ce que la bande montre réellement (postes/terminaux) + service « client courant » + lien Forge/fil d'Ariane qui le suivent | 🟠 |
| SF-199-02 | Clic du Fil : navigation unifiée, client affiché sur les tâches d'un autre client, tests | 🟢 |
