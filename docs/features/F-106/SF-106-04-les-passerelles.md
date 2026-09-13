# Mini-spec — F-106 / SF-106-04 — Les passerelles

## Identifiant

`F-106 / SF-106-04`

## Feature parente

`F-106` — La Vigie, l'espace du pilotage (cadrage : `CADRAGE-F-106-la-vigie.md` §4, §6)

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-106-04-passerelles`

---

## Objectif

Passer d'un espace à l'autre **sans perdre le client ouvert**, relier un client de la Vigie à sa
page dans la Forge, et réserver l'adresse d'un sujet de la Vigie (`/vigie/:hostRef/sujets/:id`).

---

## Comportement attendu

### Cas nominal

1. **Barre du haut** : sur `/forge/<id>`, l'entrée **Vigie** mène à `/vigie/<id>` ; sur
   `/vigie/<id>` (et `/vigie/<id>/sujets/...`), l'entrée **Forge** mène à `/forge/<id>`. Ailleurs, ou
   pour le poste « Hébergé » (`heberge`), les entrées mènent à `/forge` et `/vigie`. Si le client
   n'est pas activé dans l'espace d'arrivée, l'écran d'arrivée ouvre son client par défaut, sans
   erreur (règle existante des deux écrans).
2. **Vigie — menu du client** : « **Voir dans la Forge** » (lien vers `/forge/<id>`) quand le client
   y est activé ; sinon « Activer dans la Forge » (SF-106-02, inchangé).
3. **Adresse d'un sujet** : `/vigie/<id>/sujets/<sujet>` est une route déclarée ; tant que la page
   sujet (F-103) n'existe pas, elle **redirige** vers `/vigie/<id>?onglet=radar` — un lien vers un
   sujet ne casse jamais et ouvre le bon client.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Client absent de l'espace d'arrivée | client par défaut de cet espace | — |
| `/vigie/<id>/sujets/<sujet>` d'un client inconnu | redirigé, puis client par défaut | — |
| `/forge/voir`, `/forge/supervision` | ne sont pas pris pour un client : la Vigie mène à `/vigie` | — |

---

## Critères d'acceptation

- [ ] Depuis `/forge/h1`, l'entrée Vigie a pour adresse `/vigie/h1` ; depuis `/vigie/h1`, l'entrée
      Forge a pour adresse `/forge/h1` ; ailleurs, `/forge` et `/vigie`.
- [ ] Le menu d'un client de la Vigie activé dans la Forge porte « Voir dans la Forge ».
- [ ] `/vigie/h1/sujets/s1` mène à `/vigie/h1?onglet=radar`.

---

## Périmètre

### Hors scope (explicite)

- **Les liens sujet ↔ projet** (« Voir le projet dans la Forge » sur un sujet, « *n* sujets dans la
  Vigie » sur un projet). **Non livrables ici** : le registre du Radar (F-99) ne porte **aucun
  rattachement d'un sujet à un projet**, et le créer demande une table (donc un numéro de migration
  non attribué à cette vague) et une règle de rattachement que le cadrage ne fixe pas (déclaré par
  l'utilisateur ? déduit par l'analyse F-101 ?). Il n'existe pas non plus d'écran de sujet (F-102,
  F-103) où poser le lien. Deviner le lien par ressemblance de noms serait un rattachement inventé.
  → Question au PO, tracée dans le retour de livraison.
- La page sujet elle-même (F-103).

---

## Valeurs initiales

Aucune.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| référence de client dans l'URL | Non | identifiant ; `heberge` et segments réservés exclus | — |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `shared/space-links.ts` (nouveau) — fonction pure : client ouvert d'une adresse, adresses des deux
  entrées.
- `ShellComponent` — entrées Forge et Vigie calculées.
- `app.routes.ts` — route `vigie/:hostRef/sujets/:subjectId` (redirection).
- `VigieComponent` — « Voir dans la Forge ».

### Préoccupations transversales

- **Navigation / routing : oui.** Composants vérifiés : `ShellComponent` (liens et états actifs
  `forgeActive` / `vigieActive`, dont `/vigie/<id>/sujets/...`), `app.routes.ts` (`vigieMatcher`
  n'avale pas les chemins à 4 segments ; `forgeMatcher` inchangé ; segments réservés
  `supervision`, `mosaique`, `voir` non pris pour des clients), `VigieComponent` et
  `PostesComponent` (référence inconnue ⇒ défaut, inchangé).
- Auth / Principal, tenant, plans / limites : non.

---

## Plan de test

- [ ] `space-links.spec.ts` — client lu de `/forge/h1`, `/forge/h1?onglet=carte`, `/vigie/h1`,
      `/vigie/h1/sujets/s1` ; ignoré pour `/forge/voir`, `/forge/heberge`, `/chat`.
- [ ] `shell.component.spec.ts` — adresses des entrées selon l'adresse courante ; Vigie active sur
      une adresse de sujet.
- [ ] `app.routes.spec.ts` — la route de sujet redirige vers l'onglet Radar du client.
- [ ] `vigie.component.spec.ts` — « Voir dans la Forge » pour un client activé dans la Forge.

### Isolation workspace

- [x] Non applicable — aucune donnée lue.

---

## Dépendances

### Subfeatures bloquantes

- SF-106-02, SF-106-03 — `done`.

### Questions ouvertes impactées

- Aucune ouverte ; la question du rattachement sujet ↔ projet est posée au PO (retour de livraison).
