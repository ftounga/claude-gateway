# Mini-spec — [F-173 / SF-173-07] Agir depuis la carte

## Identifiant

`F-173 / SF-173-07`

## Feature parente

`F-173` — La carte vivante (cadrage `CADRAGE-F-173-la-carte-vivante.md`, D1→D7 validées le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-173-07-agir-depuis-la-carte`

---

## Objectif

Depuis une ressource, une échéance, un « à cartographier » ou une proposition de consolidation (F-174 SF-06), **« Demander à la Forge »** : ouvrir le terminal du poste avec une consigne **pré-remplie et non envoyée** (D7).

---

## Comportement attendu

### Cas nominal

1. Un bouton **« Demander à la Forge »** (`mat-stroked-button`, icône `auto_awesome`) apparaît :
   - dans la **fiche** d'une ressource (SF-173-03) — consigne : *« Sur la carte de ce poste, fais le point sur « X » (type) : vérifie ce qui est encore vrai, complète ce qui manque et signale les pièges. Montre-moi les changements avant de les écrire dans la carte. »* (si la ressource a des pièges ou est périmée, la consigne le rappelle) ;
   - sur chaque **échéance** (vue Signaux) — *« … l'échéance du JJ/MM/AAAA : « texte » (fichier § section). Dis-moi comment la renouveler… »* ;
   - sur chaque **« à cartographier »** — *« … cartographie « X » : … »* ;
   - sur chaque **proposition de consolidation** — la `request` rendue par `GET /api/governance/hosts/{hostRef}/map/consolidation` (SF-174-06), telle quelle.
2. Le geste appelle `AtelierService.openHostTerminal(hostId)` (idempotent, existant F-85) puis navigue vers `/atelier/{terminalId}` avec la consigne dans l'**état de navigation** (`radarDraft`, mécanisme F-103 existant) : le terminal la dépose dans la zone de saisie **sans l'envoyer**. L'utilisateur relit et envoie.
3. **Consolidation** : la vue Signaux gagne une section « La carte gagnerait à être consolidée (n) », lue par l'endpoint SF-174-06 à l'ouverture de la vue, chaque proposition avec son résumé, ses faits cités (fichier § section · Ln) et « Demander à la Forge de consolider ».
4. Poste **hors ligne** : le bouton reste visible mais désactivé, avec « Poste hors ligne : la Forge ne peut pas agir » (le plan reste consultable, D1).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Terminal non ouvert (refus d'accès, erreur) | `MatSnackBar` « Le terminal de ce poste n'a pas pu être ouvert. Rien n'a été envoyé. » ; aucune navigation |
| Consolidation illisible | section absente (silencieux, comme les autres conforts) |
| Aucune proposition | « La carte se tient : rien à consolider. » |
| Double clic | un seul terminal demandé (bouton désactivé pendant l'ouverture) |

---

## Critères d'acceptation

- [x] Le bouton existe sur fiche, échéance, « à cartographier », proposition de consolidation.
- [x] Il ouvre le terminal du poste avec la consigne pré-remplie, **jamais envoyée**.
- [x] Désactivé poste hors ligne ; erreur dite sans navigation.
- [x] Les propositions F-174 SF-06 s'affichent avec leurs faits et leur demande.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Texte cité dans une consigne | ≤ 300 caractères (tronqué « … ») |
| Consigne | en français, se termine par la demande de relecture avant écriture |

---

## Périmètre

### Hors scope (explicite)

- Tout envoi automatique, toute écriture de la carte par l'écran (F-174 D1).
- Toute modification du terminal (le dépôt de brouillon F-103 est réutilisé tel quel).

---

## Technique

### Contrat API

- Consommé : `GET /api/governance/hosts/{hostRef}/map/consolidation` (SF-174-06, sur `main`) → `{indexed, total, proposals[{kind, path, summary, facts[{path, heading, lineNo, text}], request}]}`.
- Consommé : `POST /api/runner-hosts/{hostId}/terminal` via `AtelierService.openHostTerminal` (existant, idempotent).

### Composants frontend

- `core/models/governance.models.ts` (+ `MapConsolidation`), `core/services/governance.service.ts` (+ `hostMapConsolidation`).
- `postes/forge-map/forge-map-ask.ts` : fabriques de consignes (pures).
- `postes/forge-map/forge-map.component.*` : input `online`, input `hostId`, geste `ask`, section consolidation.
- `postes/postes.component.html` : passe `online` et `hostId`.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Navigation / routing | Oui | `/atelier/:id` avec `state.radarDraft` (lu par `AtelierComponent.takeRadarDraft`, F-103, inchangé) ; `ForgeMapComponent.ask` ; aucune route nouvelle |
| Auth | Non (garde existante de `openHostTerminal`, refus d'accès rendu par la gateway) | — |
| Tenant, plans | Non | — |

---

## Plan de test

- [x] `forge-map-ask.spec.ts` : consignes (ressource, piège / périmé, échéance, à cartographier), troncature, relecture exigée.
- [x] `forge-map.component.spec.ts` : clic → `openHostTerminal` puis navigation `/atelier/<id>` avec `radarDraft` ; erreur → snackbar sans navigation ; hors ligne → désactivé ; consolidation affichée et demandée.
- [x] `governance.service.spec.ts` : URL de la consolidation.
- [x] `npm run build && npm test` verts.

## Dépendances

SF-173-03, SF-173-06 mergées ; F-174 SF-174-06 mergée.

## Notes et décisions

- **Arbitrage (réversible)** : réutiliser le dépôt de brouillon du Radar (F-103, `history.state.radarDraft`) plutôt qu'un nouveau mécanisme — il fait exactement D7 (déposé, non envoyé, non redéposé au rechargement).
