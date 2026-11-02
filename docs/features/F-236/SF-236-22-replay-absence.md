# Mini-spec — [F-236 / SF-236-22] Replay visuel d'un tour + « Pendant votre absence »

## Identifiant
SF-236-22

## Feature parente
F-236 — Jarvis en 3D (cadrage §13 NF-9 / NF-10, §15 ; itération 4 « Au-delà »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-22-replay-absence`

---

## Objectif

Revoir en quelques secondes ce que l'agent a fait pendant un tour (scènes, nœuds cités, deux cerveaux), à ×1 ou ×4,
en pause ou image par image — et, à l'ouverture, enchaîner les tours faits depuis la dernière visite.

## Comportement attendu

### Cas nominal

1. **Lecteur privé (pur, `hud/jarvis-replay.ts`)** — les étapes d'un tour sont lues dans sa transcription persistée
   (outil + cible, sortie reçue ; ≤ 60) et rejouées dans une **instance séparée** de `JarvisSceneReader`, sur une
   horloge simulée (2 s par étape à ×1, 1,5 s de fin, une image toutes les 250 ms). Résultat : une suite d'images
   (scène, nœuds cités, appels, étapes) **déterministe**.
2. **Lecture (`ReplayPlayer`)** : ×1 / ×4, pause, image par image (◀ ▶, pause automatique), tour suivant, fermer ;
   en fin de tour, le suivant s'enchaîne (NF-10).
3. **Écran** : tant que le replay joue, `stageSignals()` substitue l'image du lecteur privé à celle du bus (état
   « réflexion », `turnActive` ⇒ les deux cerveaux se déploient comme pendant le tour). Le bus vivant n'est **jamais**
   alimenté. Barre de lecture (T-R) en verre holographique, visible en 3D **et** en 2D (vue simplifiée : libellé de
   la scène).
4. **Entrées** : « ▶ Rejouer ce tour » dans le résumé d'un tour de la chronologie de la journée (cockpit et volets
   téléphone ; tour déjà lu, aucun appel) ; `?replay=<messageId>` (le tour du sujet courant) ; NF-10 à l'ouverture.
5. **« Pendant votre absence » (NF-10)** : la dernière visite est retenue **par appareil** ; à l'ouverture, s'il y en
   a une, l'historique du sujet est lu **une fois, hors tour** ; les tours faits depuis (avec au moins une action,
   ≤ 12) sont proposés : « n tours depuis votre dernière visite — ▶ Voir ce qui s'est passé ».
6. **Clavier** : Échap ferme, Espace met en pause, ← → image par image (ignorés dans un champ de saisie).

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Tour sans action, ou `?replay=` inconnu | Rien n'est joué (aucune erreur). |
| Historique illisible | Rien n'est proposé. |
| Un tour vivant démarre | Le replay s'arrête (le direct prime) ; aucun replay ne démarre pendant un tour. |
| Aucune visite précédente / stockage indisponible | Aucune proposition NF-10, aucun appel. |

---

## Critères d'acceptation

- [x] CA1 — Le bus vivant n'est jamais alimenté par le replay (test : aucune méthode du bus appelée, état identique).
- [x] CA2 — Même tour ⇒ même suite de scènes (test déterministe).
- [x] CA3 — ×1 / ×4, pause, image par image, enchaînement (tests du lecteur).
- [x] CA4 — NF-10 : tours depuis la dernière visite, bornés (test) ; refusé pendant un tour (test cockpit).
- [x] CA5 — Isolation `user_id` héritée de l'historique (`GET /api/workspaces/{id}/chat`, inchangé).
- [x] CA6 — Banc `cockpit-replay` (capture dans la planche de l'itération 4, SF-236-27).

## Plan de test

- Unitaires : `hud/jarvis-replay.spec.ts` (étapes, déterminisme, scènes du registre, secrets masqués, nœuds cités,
  `trackOf`, NF-10, lecteur).
- Composant : `jarvis.component.spec.ts` (substitution, bus jamais alimenté, barre affichée, arrêt) ;
  `jarvis-cockpit.component.spec.ts` (« Rejouer ce tour » sans nouvel appel, refusé pendant un tour).
- Isolation utilisateur : héritée (lecture existante filtrée `user_id`), aucun endpoint nouveau.

## Périmètre

### Impacté
`hud/jarvis-replay.ts`, `hud/jarvis-replay.component.{ts,html,scss}`, `hud/jarvis-cockpit.component.{ts,html}`
(`replay`), `jarvis.component.{ts,html}` (`replayView`, `?replay=`), banc (`cockpit-replay`).

### Hors scope (explicite)
- Compteur `replays` de la mesure (clé fermée côté serveur) : ajouté avec l'agrégat de SF-236-26.
- Replay des sous-agents et de la voix (les phrases dites ne sont pas persistées en temps).
- Aucun endpoint, table, appel IA ni consigne.

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Navigation / routing | Oui | `/jarvis` : paramètre nouveau `?replay=<messageId>` lu à l'ouverture ; `?sujet=`, `?alerte=`, `?source=compagnon` inchangés (tests existants verts). Aucune route. |
| Contexte tenant | Non | Lecture existante `getHistory` (isolée `user_id`). |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | 2 s par étape à ×1 (un tour de 40 actions ≈ 20 s à ×4). | Cible « revoir en 20 s » du cadrage. | Temps réels (non persistés par étape). | Oui. |
| A2 | Dernière visite en `localStorage` (par appareil). | Confort par appareil ; rien côté serveur (§19 : aucune écriture). | Colonne serveur. | Oui. |
| A3 | NF-10 sur le sujet courant seulement. | L'historique se lit par sujet ; un seul appel à l'ouverture. | Tous les sujets (N appels). | Oui. |
| A4 | Captures regroupées dans la planche SF-236-27. | Un seul serveur de développement pour les 6 SF de l'itération. | Captures par SF. | Oui. |
