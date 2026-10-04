# Mini-spec — [F-173 / SF-173-06] Les signaux

## Identifiant

`F-173 / SF-173-06`

## Feature parente

`F-173` — La carte vivante (cadrage `CADRAGE-F-173-la-carte-vivante.md`, D1→D7 validées le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-173-06-signaux`

---

## Objectif

Faire voir ce qui demande attention sur la carte : **pièges** sur les nœuds, faits **périmés** pâlis, **échéancier** des accès et jetons qui expirent, liste **« à cartographier »**.

---

## Comportement attendu

### Cas nominal

1. Sur le plan et la liste (déjà en SF-173-02) : pièges = filet rouge + « ⚠ n pièges » écrit ; périmé = pâli + « périmé » ; à cartographier = « à cartographier » écrit. Cette SF ajoute une **légende** sous le plan qui le dit en mots.
2. Une vue **Signaux** s'ajoute à la bascule (*Plan · Liste · Grille · Signaux · Fichiers*, `?vue-carte=signaux`), calculée sur le plan déjà lu :
   - **Échéancier** : les échéances (contrat SF-173-01 `deadlines`) triées par date, en trois groupes — **Dépassées** (rouge erreur, « dépassée depuis n j »), **Dans les 14 jours** (ambre §12 `#F9A825`, « dans n j »), **Plus tard** ; chaque ligne : date, texte, ressource (cliquable → fiche), source `fichier § section · Ln`.
   - **À cartographier** : la liste `toMap` — ressource (cliquable) ou texte, avec sa source.
   - **Résumé** : n pièges, n ressources périmées, n échéances dépassées.
3. Le bouton de la vue Signaux porte un compteur quand une échéance est dépassée ou à moins de 14 jours (« Signaux · 3 »).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucune échéance | « Aucune échéance datée dans la carte. » |
| Rien à cartographier | « Rien n'est marqué à cartographier. » |
| Échéance sans ressource rattachée | ligne non cliquable, texte et source seuls |
| Liste bornée par le serveur (200) | inchangé, l'API borne |

---

## Critères d'acceptation

- [x] Échéances groupées dépassées / ≤ 14 j / plus tard, triées, chacune avec sa source.
- [x] « À cartographier » listé avec source ; clic → fiche quand une ressource est rattachée.
- [x] Légende sous le plan ; compteur sur « Signaux ».
- [x] Couleur jamais seule : chaque état est écrit.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Seuil « bientôt » | 14 jours (aligné sur F-174 SF-04 ±14 j) |
| Date du jour | horloge du navigateur, à minuit local |

---

## Périmètre

### Hors scope (explicite)

- Notifications / Web Push d'échéance.
- Toute écriture ; « Demander à la Forge » sur ces lignes (SF-173-07).

---

## Technique

### Contrat API

Importé de SF-173-01 (`deadlines`, `toMap`, `traps`, `stale`), sans modification.

### Composants frontend

- `postes/forge-map/forge-map-signals.ts` : fonctions pures (`deadlineGroups`, `daysBetween`, `signalCount`, `signalSummary`).
- `postes/forge-map/forge-map.component.*` : vue `signaux`, légende, compteur.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Navigation / routing | Oui | nouvelle valeur `signaux` de `?vue-carte=` (`mapViewFrom`, `selectView`) ; `?onglet=`, `/forge/:hostRef` inchangés |
| Auth, tenant, plans | Non | — |

---

## Plan de test

- [x] `forge-map-signals.spec.ts` : groupes, jours, compteur, résumé.
- [x] `forge-map.component.spec.ts` : vue Signaux, ressource cliquable, listes vides, compteur.
- [x] `npm run build && npm test` verts.

## Dépendances

SF-173-02 mergée.

## Notes et décisions

- **Arbitrage (réversible)** : ambre §12 (`#F9A825`, « ce qui attend ») pour « dans les 14 jours » — couleur déjà au design system, jamais seule (« dans n j » écrit).
