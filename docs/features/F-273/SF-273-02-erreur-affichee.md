# Mini-spec — [F-273 / SF-273-02] L'erreur réelle affichée : « Ce que je sais » et terminal

## Identifiant

`F-273 / SF-273-02`

## Feature parente

`F-273` — Sondes de relevé : l'erreur réelle, lisible

## Statut

`in-progress`

## Date de création

2026-11-21

## Branche Git

`feat/SF-273-02-erreur-affichee`

---

## Objectif

Que le PO **lise** pourquoi une sonde est en échec, là où il la voit : le volet « Ce que je sais » (relevé du
client, « Tout valider ») et la bande des veilles en tête du fil du sujet.

## Comportement attendu

### Cas nominal

1. **« Ce que je sais » › Relevé** : chaque sonde affiche son statut, puis, si `lastErrorMessage` est présent, une
   ligne d'erreur (`role="alert"`, couleur `--cg-orange-2`) « Échec du test · 21/11 10:12 — <message> » (sonde
   proposée) ou « Dernier passage en échec · … — <message> » (sonde validée). Statut : « À valider (test en
   échec) » pour une proposition en échec, « En échec » pour une sonde active dont le dernier passage a échoué.
2. **« Tout valider »** : le snack garde le compte ; la liste des sondes est relue (les erreurs gardées s'affichent).
   Un **409** (poste hors ligne) affiche son message réel au lieu de « Validation impossible ».
3. **Terminal, bande des veilles** : une veille proposée affiche, sans nouveau test dans la visite, son dernier
   échec gardé (« Dernier test en échec : <message> ») ; une veille active ou en panne dont le dernier passage a
   échoué affiche la raison (« Dernier passage en échec : <message> ») — y compris un relevé (`purpose = STATE`),
   jusque-là invisible au terminal tant qu'il n'était pas « en panne ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| « Tout valider » poste hors ligne | snack avec le message du serveur | 409 |
| Lecture des veilles en échec réseau | bande vide (inchangé) | — |

## Critères d'acceptation

- [ ] `releveProbeStatus` : « À valider (test en échec) », « En échec » selon `lastErrorCode` / `lastRunStatus`.
- [ ] La ligne d'erreur du relevé affiche date et message ; absente sans erreur.
- [ ] « Tout valider » en 409 montre le message du serveur.
- [ ] La bande des veilles montre l'échec gardé d'une proposition et l'échec du dernier passage d'une veille validée.
- [ ] Couleurs et polices du design system uniquement (`--cg-orange-2`, `--cg-font-body`), espacements multiples de 4 px.

## Périmètre

### Hors scope (explicite)

- Backend (SF-273-01), outil de l'agent (SF-273-03). Aucun nouvel écran, aucune nouvelle route.

## Contraintes de validation

Aucune saisie.

## Technique

### Endpoint(s)

Aucun nouveau (lit les champs `lastErrorCode/Message/At` ajoutés par SF-273-01).

### Tables impactées

Aucune.

### Composants Angular

- `jarvis-state-panel.component.ts` — ligne d'erreur sous chaque sonde, message 409 de « Tout valider ».
- `jarvis-state.ts` — `ReleveProbe` + `releveProbeStatus`.
- `terminal-veilles-band.component.ts` + `veille.models.ts` — échec gardé / dernier passage en échec.

## Plan de test

### Tests unitaires

- [ ] `jarvis-state.spec.ts` — `releveProbeStatus` (proposée en échec, active en échec, nominal).
- [ ] `terminal-veilles-band.component.spec.ts` — échec gardé d'une proposition, échec de passage d'une veille active.
- [ ] `jarvis-state-panel.component.spec.ts` — ligne d'erreur rendue ; 409 de « Tout valider » ⇒ message serveur.

### Tests d'intégration

- Non applicable (frontend ; contrat couvert par SF-273-01).

### Isolation workspace

- [x] Non applicable — aucune nouvelle lecture ; les routes existantes filtrent déjà par compte.

## Dépendances

- SF-273-01 — mergée avant cette branche.

## Notes et décisions

- Préoccupations transversales : aucune.
