# Mini-spec — [F-212 / SF-212-06] Les captures de preuve visibles partout (correctif)

## Identifiant

`F-212 / SF-212-06`

## Feature parente

`F-212` — Les captures de preuve

## Statut

`in-review`

## Date de création

2026-10-31

## Branche Git

`feat/SF-212-06-preuves-visibles`

---

## Objectif

Le PO voit toujours l'image d'une capture de preuve — au rechargement, dans un volet de chantier, en direct, et sous l'encart ```message preuves=<id>``` — au lieu de rien.

---

## Constat (31/10, terminal agenor `d5f10db2…`, chantier 1)

- 3 captures produites et rangées (`proof_captures` f5700da2…, a5e23a3a…, 91c46755…).
- Le compte rendu persisté (`atelier_messages.terminal_json`) **porte bien** le bloc `proof` : message `ed298811…` (2 blocs `proof` : f5700da2 + a5e23a3a, encart `preuves=a5e23a3a…`), message `33538c53…` (bloc `proof` 91c46755, encart `preuves=91c46755…`). Le backend n'est pas en cause.
- Le PO ne voit pourtant jamais l'image.

## Causes (confirmées)

1. **Relecture de l'historique** — `toThreadItem` (`frontend/src/app/atelier/thread-item.ts`) recopie chaque bloc du compte rendu sans ses champs riches (`proof`, mais aussi `card`, `email`, `page`, `attente`, `handoff`, `proposal`, `besoin`, `video`, `audio`). Toute relecture (rechargement, retour dans le terminal, et TOUJOURS dans un volet de chantier qui relit `GET /chat?chantier=N` en fin de tour) perd les captures : ni bloc de preuve, ni image sous l'encart. Le défaut existe depuis F-30 et frappe tous les blocs riches.
2. **Volet de chantier en direct** — `TerminalLanePaneComponent` ne branche aucun événement SSE riche (`proof`, `page`, `email`, `card`, `attente`, `handoff`, `proposal`, `besoin`, `video`, `audio`) : en direct dans un fil de chantier, la capture n'apparaît pas.
3. **Encart en direct** — pendant le tour, l'encart `preuves=` ne cherche que dans `messages` (tours finis), jamais dans les blocs du tour en cours : une capture du tour vivant n'est pas trouvée.
4. **Capture absente du fil chargé** — un `preuves=<id>` dont la capture vient d'un autre fil (sujet vs chantier) ou d'un tour non chargé ne rend rien.

## Comportement attendu

### Cas nominal

- `toThreadItem` conserve tous les champs riches d'un bloc persisté (dont `proof`).
- Le volet de chantier range les blocs riches reçus en direct comme l'écran principal (`withCards`, ordre conservé au recalcul des étapes).
- L'encart `preuves=<ids>` cherche les captures dans le fil chargé ET dans les blocs du tour en cours.
- Repli : un identifiant introuvable est affiché quand même, depuis la route existante `GET /api/workspaces/{id}/proofs/{captureId}`, dans le même bloc de preuve (vignette, zoom, Copier l'image, Télécharger) ; « Copier » de l'encart emporte ces images (SF-212-05).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Identifiant `preuves=` qui n'existe pas (ou d'un autre utilisateur) | La route refuse ; la vignette affiche « Image indisponible. » (comportement existant du composant) | 404 |
| Bloc persisté sans champ riche (tours anciens) | Relu comme avant, sans champ riche | — |
| `preuves=` mal formé (non hexadécimal 32) | Ignoré par l'analyseur existant ; repli « dernier encart du tour » | — |

---

## Critères d'acceptation

- [ ] `toThreadItem` conserve `proof` (et les autres blocs riches) d'un bloc persisté.
- [ ] Un encart `message preuves=<id>` relu depuis l'historique affiche l'image sous l'encart.
- [ ] Volet de chantier : un événement `proof` en direct affiche le bloc ; après relecture du fil, il reste affiché.
- [ ] Une capture référencée mais absente du fil chargé s'affiche par la route d'image (repli), avec Copier/Télécharger.
- [ ] Pendant le tour, l'encart `preuves=` trouve une capture du tour en cours.
- [ ] Backend : le bloc persisté porte le `proof` (y compris après bornage de la sortie) et `AtelierMessageResponse` le renvoie.
- [ ] Isolation : l'image passe toujours par la route isolée du propriétaire (`user_id` + workspace) ; aucun nouvel accès données.

---

## Périmètre

### Hors scope (explicite)

- Aucun changement du rendu des images, de l'outil `capturer_preuves`, du runner ni du stockage.
- Pas de nouvel endpoint, pas de migration.
- Pas de rechargement des tours anciens au-delà de ce que `GET /chat` rend déjà.

---

## Contraintes de validation

Aucune nouvelle. L'analyseur `preuves=` existant (32 hexadécimaux) est conservé.

---

## Technique

### Endpoint(s)

Aucun nouveau. Route lue : `GET /api/workspaces/{id}/proofs/{captureId}` (existante, isolée).

### Tables impactées

Aucune (lecture seule de `atelier_messages.terminal_json` par l'existant).

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `thread-item.ts` — conserve les champs riches.
- `atelier.models.ts` — `AtelierPersistedTranscript.blocks[]` porte les champs riches optionnels.
- `AtelierTerminalComponent.attachedProof` — cherche aussi dans le tour vivant ; repli par identifiant.
- `TerminalLanePaneComponent` — branche les événements riches du direct.

### Préoccupations transversales

Aucune cochée (auth, tenant, plans, navigation inchangés).

---

## Plan de test

### Tests unitaires (front)

- [ ] `thread-item.spec.ts` — `proof` conservé, autres champs riches conservés, bloc sans champ riche inchangé.
- [ ] `terminal-proof-attached.spec.ts` — encart relu (via `toThreadItem`) → image affichée ; capture absente → repli par la route ; capture du tour vivant trouvée.
- [ ] `terminal-lane-pane.component.spec.ts` — événement `proof` en direct → bloc dans le tour vivant ; relecture → bloc conservé.

### Tests backend

- [ ] `AtelierTurnReportProofTest` — `proof` sérialisé, conservé au bornage, renvoyé par `AtelierMessageResponse`.

### Isolation workspace

- [x] Non applicable — aucun nouvel accès données ; l'image passe par la route existante déjà isolée (testée en SF-212-03).

---

## Dépendances

- SF-212-01→05 — done.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- Le correctif de `toThreadItem` couvre TOUS les blocs riches (même défaut, même ligne) : les cartes Teams, pages, courriels, attentes, passations, propositions, besoins, vidéos et sons survivent désormais aussi au rechargement, comme le backend le prévoyait (« survit au rechargement »).
- Le repli affiche un bloc titré « Captures jointes » sans relevés de commandes (ils ne sont pas dans le fil chargé).
