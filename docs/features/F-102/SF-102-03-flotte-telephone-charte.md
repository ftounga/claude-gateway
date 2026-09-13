# Mini-spec — F-102 / SF-102-03 — Flotte, téléphone, charte

## Identifiant

`F-102 / SF-102-03`

## Feature parente

`F-102` — Le Radar et le résumé du matin, **dans la Vigie** (cadrage commun :
`docs/features/F-99/CADRAGE-le-radar.md` §8, §12 bis ; maquette `docs/features/F-99/maquette-radar.html`)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-102-03-flotte-telephone-charte`

---

## Objectif

Porter le compte du Radar **hors de l'onglet** — « *k* à traiter » sur l'onglet Radar et dans le bandeau de la
Vigie, relus d'une seule lecture par client et rafraîchis après chaque geste —, tenir l'écran au téléphone, et
écrire **`DESIGN_SYSTEM.md` §17 — Le Radar** sans couleur hors charte.

---

## Comportement attendu

### Cas nominal

1. **Une lecture par client** : les compteurs du bandeau et de la colonne (`VigieService.radarCounts`) viennent
   désormais de `GET /radar/hosts/{id}/brief` (SF-102-01) au lieu de trois lectures (engagements, sujets
   bloqués, synchros). Ils portent `followUpsDue`, `blockedSubjects`, **`toHandle`** et la synchro à dire
   (`running`, sinon `lastSync`). Toujours **silencieux** : un résumé illisible compte zéro et « aucune
   synchro », jamais une erreur. Toujours lus une fois par page, jamais au sondage.
2. **Onglet Radar** : la pastille dit « *k* à traiter » (`badge--warning`, §12) quand `toHandle > 0` — ce qui
   réclame un geste — au lieu du seul nombre de relances.
3. **Bandeau de la Vigie** : après « *k* relances dues » (inchangé, ambre §12), un fait « ***k*** à traiter »
   (encre de bandeau, chiffre en encre principale) quand le total de la flotte est non nul. La colonne des
   clients garde « *k* relance(s) » : c'est la relance due qui range un client dans *À regarder* (F-106).
4. **Rafraîchi après un geste** : quand le résumé d'un client est relu (après un geste, une synchro, un
   *Réessayer*), la Vigie remplace les compteurs de ce client par ceux du résumé : onglet, bandeau et colonne
   changent sans relire la page.
5. **Téléphone** (mise en page, pas de nouvel écran) : sous 860 px le résumé et la couverture s'empilent
   (résumé d'abord) ; sous 1020 px deux colonnes avec *Sujets en cours* sur toute la largeur ; sous 640 px
   une seule colonne, *À faire par moi* d'abord ; les boutons de geste passent à la ligne ; aucun
   défilement horizontal. Le détail d'un client au téléphone reste celui de F-106 (`/vigie/<id>` plein écran).
6. **`DESIGN_SYSTEM.md` §17 — Le Radar** : les registres employés et **aucune couleur nouvelle** :
   - états de sujet : `avance` §5 succès, `en attente` et `silencieux` §5 attente (§12), `bloqué` §5 erreur,
     `en sommeil` et `clos` §5 neutre, **`nouveau`, `clos ?`, `se réveille` en bleu §9 index 0** (`#386599`
     sur `#E7EFF9`, contraste AA déjà prouvé par `host-identity.spec.ts`) — exception validée au cadrage §8 ;
   - ce qui est dû : **filet orange de 4 px** (`--cg-orange`), jamais un fond ; tuile de compteur « chaude » :
     même filet, chiffre en encre principale ;
   - couverture : ✓ `--cg-success`, ! ambre §12, avertissement **en tête** avec filet ambre et pastille
     « Couverture incomplète » ; manques jamais repliés ;
   - icônes de source (Material) : message Teams `forum`, réunion `videocam`, enregistrement `mic`, note
     `edit_note`, courriel collé `mail`, canal `tag` ;
   - rendu d'une preuve dans une colonne : icône de source + « dans « *sujet* » » + moment (« hier 14:32 ») +
     *Ouvrir la source* (nouvel onglet, `noopener noreferrer`) ;
   - certitude : `probable` écrit en toutes lettres, la ligne se lit comme une question ; **jamais un score** ;
   - personnes : écrites en toutes lettres, **sans pastille de couleur** (§9 réservé aux machines) ;
   - téléphone : les ruptures ci-dessus.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Résumé d'un client illisible (réseau, droit retiré, client hors Vigie) | compteurs à zéro, « aucune synchro encore », aucun message | 403 / 409 / — |
| Poste d'autrui | jamais listé dans la Vigie ; lecture refusée | 404 |
| Résumé relu en échec après un geste | les compteurs précédents restent | — |

---

## Critères d'acceptation

- [ ] CA1 — `radarCounts` fait **une** lecture (`/brief`) et rend `followUpsDue`, `blockedSubjects`, `toHandle`, la synchro (en cours d'abord).
- [ ] CA2 — Un résumé illisible compte zéro sans erreur.
- [ ] CA3 — L'onglet Radar porte « *k* à traiter » quand `toHandle > 0`, rien sinon.
- [ ] CA4 — Le bandeau ajoute « *k* à traiter » (total flotte) quand non nul ; « relances dues » inchangé.
- [ ] CA5 — Un résumé relu met à jour les compteurs du client (onglet, bandeau, colonne).
- [ ] CA6 — Mise en page téléphone : ruptures 860 / 1020 / 640 px en place, gestes à la ligne.
- [ ] CA7 — `DESIGN_SYSTEM.md` §17 écrit ; aucune couleur hors §2, §5, §9 index 0, §12 dans les composants du Radar.

---

## Périmètre

### Hors scope (explicite)

- Une application mobile ou un écran téléphone distinct.
- Changer la règle *À regarder* de la colonne (relance due, F-106).
- La page sujet (F-103), *Donner la nouvelle* (F-104).
- Toute nouvelle couleur.

---

## Valeurs initiales

Non applicable.

---

## Contraintes de validation

Non applicable — aucune saisie.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/brief` | existant (SF-102-01) | droit Teams + client dans la Vigie |

### Tables impactées

Aucune écriture ; lectures de SF-102-01.

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

- `VigieService.radarCounts` — une lecture du résumé.
- `vigie.models.ts` — `VigieRadarCounts.toHandle`.
- `vigie-fleet.ts` — `fleetSummary` additionne `toHandle` ; `toHandleLabel`.
- `VigieComponent` — pastille « à traiter » de l'onglet, fait du bandeau, compteurs rafraîchis par `briefChange`.
- `docs/DESIGN_SYSTEM.md` — §17.

### Préoccupations transversales

- **Plans / limites : non** — lecture sous la même garde.
- **Navigation : non** — aucune route touchée.
- **Contexte tenant : non.**
- **Auth / Principal : non.**

---

## Plan de test

### Tests unitaires

- [ ] `vigie.service.spec.ts` — une lecture `/brief` ; synchro en cours d'abord ; illisible = zéro.
- [ ] `vigie-fleet.spec.ts` — somme de `toHandle` ; libellé.
- [ ] `vigie.component.spec.ts` — pastille « à traiter » de l'onglet ; fait du bandeau ; rafraîchissement par `briefChange`.

### Tests d'intégration

- [ ] Couverts par `RadarBriefApiIntegrationTest` (SF-102-01) : compteurs et isolation.

### Isolation utilisateur

- [x] Applicable — la lecture est celle du résumé, isolée par poste (`user_id` + `host_id`), testée en SF-102-01.

---

## Dépendances

### Subfeatures bloquantes

- SF-102-01 — `done` ; SF-102-02 — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Le bleu §9 index 0 qualifie un état** (`nouveau`, `clos ?`, `se réveille`) : exception au §9 (« un seul
  usage : identifier un poste ») **décidée au cadrage validé** (§8). Elle reste bornée à la pastille d'état du
  Radar, jamais un filet ni une surface, pour ne pas se confondre avec l'identité d'un poste.
- La maquette colorait les chiffres « chauds » en orange foncé (`#B85E22`, hors charte) : remplacé par le filet
  orange de la charte, le chiffre restant en encre principale (contraste).
