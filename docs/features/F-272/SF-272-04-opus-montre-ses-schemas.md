# Mini-spec — [F-272 / SF-272-04] Opus montre ses schémas, en 3D, sans demander confirmation

## Identifiant
`F-272 / SF-272-04`

## Feature parente
`F-272` — Jarvis : corrections de la démo du 17/11

## Statut
`in-progress`

## Date de création
2026-11-18

## Branche Git
`feat/SF-272-04-opus-montre-ses-schemas`

---

## Objectif
Un schéma demandé à Jarvis est dessiné sans « vous confirmez ? » et apparaît à l'écran Jarvis (une architecture en
3D), et Opus ne dit plus que la 3D n'existe pas.

## Causes racines (prouvées)
- `JarvisOralNote` (v3) : le vocabulaire `🖥 montrer: pieces` ne cite que `proof:`, `page:`, `video:` — jamais `diag:`,
  aucune mention de la vue 3D (« la 3D n'est pas disponible », démo 00:03:06).
- `render_diagram` rend un chemin de fichier, jamais l'identifiant de pièce `diag:<id>` : Opus ne peut pas le montrer.
- Le schéma produit (graphe 3D prêt) n'est jamais affiché : rien ne l'ouvre à la fin du tour délégué.
- La règle « action qui engage ⇒ Vous confirmez ? » est appliquée à un dessin (tour de 50 s / 536 k jetons pour
  demander confirmation).
- Cache des pièces (60 s, par pod) : un schéma tout juste rendu est introuvable de l'écran pendant une minute.

## Comportement attendu

### Cas nominal
1. `render_diagram` (cloud, drawio, mermaid) réussi : le résultat d'outil ajoute « Pièce diag:<id> — à l'écran Jarvis,
   « 🖥 montrer: pieces diag:<id> » l'ouvre (une architecture s'ouvre en 3D). » (résultat d'outil : préfixe intact).
2. `JarvisOralNote` v4 (dans le **message**, jamais la consigne système) : `diag:<id>` dans le vocabulaire montrer ;
   « un schéma d'architecture s'ouvre en 3D à l'écran, qui sait aussi le montrer en 2D » ; après `render_diagram`,
   terminer par la ligne montrer ; « Dessiner un schéma, préparer une page ou un document, lire, chercher ou vérifier
   n'engagent rien : fais-le directement, sans demander de confirmation. »
3. Écran Jarvis, fin d'un tour Opus (délégué ou direct) dont la réponse ne montre aucun `diag:` : le schéma
   (architecture ou diagramme) produit pendant ce tour dans ce sujet est ouvert d'office (ordre `piece`, demande).
4. L'index des pièces lu par l'écran avec `fresh=true` contourne le cache (lecture d'un identifiant nommé introuvable :
   une relecture fraîche avant de refuser).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Rendu en échec | aucun identifiant, message d'erreur inchangé |
| Conservation du rendu en échec (best-effort) | pas d'identifiant, chemin seul (comme avant) |
| Aucun schéma produit pendant le tour | rien d'ouvert |
| Réponse qui montre déjà une pièce `diag:` | pas de second affichage |
| Mode présentation | rien d'ouvert (règle F-237) |

## Critères d'acceptation
- [ ] Résultat de `render_diagram` réussi : contient `diag:<id>` de la ligne conservée (test).
- [ ] `JarvisOralNote.VERSION` = 4 ; le texte contient `diag:`, « 3D », « sans demander de confirmation » (test).
- [ ] La consigne système et la liste d'outils inchangées à l'octet près (tests de préfixe existants verts).
- [ ] Fin de tour délégué sans montrer : le schéma produit depuis le début du tour est ouvert (test du composant).
- [ ] `GET /api/jarvis/pieces?fresh=true` relit sans cache ; isolation inchangée (test d'intégration).

## Plan de test
### Tests unitaires
`DiagramToolExecutorTest` (identifiant dans le résultat), `JarvisOralNoteTest` (v4), `jarvis-piece-live.spec.ts`
(choix du schéma produit), `jarvis-piece-store` (relecture fraîche).
### Tests d'intégration
`JarvisPieceApiIntegrationTest` (`fresh=true`, isolation) ; banc à vraie voix F-256 scénario `demo-1711`.
### Isolation utilisateur
`fresh=true` passe par le même `requireOwned` (test : sujet de Bob ⇒ 404).

## Tables / endpoints / composants impactés
`GET /api/jarvis/pieces` (+ paramètre facultatif `fresh`). Aucune table. `DiagramToolExecutor`, `JarvisOralNote`,
`JarvisPieceService`, `JarvisPieceController`, front `jarvis-piece-client.ts`, `jarvis-piece-store.ts`,
`jarvis-piece-live.ts`, `jarvis.component.ts`.

## Préoccupations transversales
Aucune (auth, tenant, plans, navigation : non). Préfixe de cache : consigne système et outils inchangés.

## Hors périmètre
Afficher le schéma pendant que le tour continue (nouvel événement SSE relayé entre pods) — à la fin du tour suffit.
