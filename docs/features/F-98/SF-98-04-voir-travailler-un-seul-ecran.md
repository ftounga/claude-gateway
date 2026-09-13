# Mini-spec — F-98 / SF-98-04 — Voir travailler, un seul écran

## Identifiant

`F-98 / SF-98-04`

## Feature parente

`F-98` — La Forge, refondue (cadrage : `CADRAGE-F-98-la-forge-refondue.md` §4)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-98-04-voir-travailler`

---

## Objectif

Réunir la supervision (F-76, aperçus) et la mosaïque (F-83, flux entiers) derrière **un seul bouton
« Voir travailler »** et **une seule route** `/forge/voir?densite=apercus|flux`, avec un sélecteur
**Aperçus / Flux entiers** retenu par l'utilisateur, les deux anciennes routes redirigeant.

---

## Comportement attendu

### Cas nominal

1. **La Forge** n'a plus qu'un bouton **« Voir travailler »** dans son bandeau, vers `/forge/voir`
   (sans densité : le choix retenu s'applique). Le bouton « Mosaïque » disparaît.
2. **`/forge/voir`** (`VoirTravaillerComponent`) : une barre d'une ligne — « ← Forge », le titre
   « Voir travailler », le sélecteur segmenté **Aperçus · Flux entiers** —, puis l'écran de la densité
   choisie, **inchangé dans sa logique** : `app-supervision` (aperçus, aucun flux) ou `app-mosaique`
   (quatre flux entiers, lecture seule, zoom).
3. **Densité effective** : `?densite=` s'il vaut `apercus` ou `flux` ; sinon le choix retenu dans
   `localStorage` (`cg_forge_densite`) ; sinon **Aperçus**.
4. **Changer de densité** : enregistre le choix (`localStorage`) et met `?densite=` à jour **en
   remplaçant** l'entrée d'historique — « retour » ramène à la Forge, pas à l'autre densité.
   Ouvrir un lien `?densite=flux` **enregistre** aussi ce choix.
5. **Anciennes routes** : `/forge/supervision` → `/forge/voir?densite=apercus`,
   `/forge/mosaique` → `/forge/voir?densite=flux` (les autres paramètres de requête sont conservés).
6. Les en-têtes propres aux deux écrans (fil d'Ariane « Supervision », titre « Mosaïque », retour à la
   Forge, lien croisé vers l'autre densité) sont retirés : la barre de `/forge/voir` les remplace.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `?densite=` inconnu | choix retenu, sinon Aperçus | — |
| `localStorage` indisponible (navigation privée, accès refusé) | Aperçus, aucune erreur ; le choix n'est simplement pas retenu | — |
| Valeur stockée illisible | Aperçus | — |
| Gateway injoignable / aucun terminal | notices existantes de chaque densité, inchangées | — |

---

## Critères d'acceptation

- [ ] La Forge n'a qu'un bouton « Voir travailler », vers `/forge/voir`.
- [ ] `/forge/voir?densite=apercus` montre les aperçus ; `?densite=flux` la mosaïque.
- [ ] Le sélecteur bascule sans changer de page, et le choix est retrouvé à la visite suivante de
      `/forge/voir` sans paramètre.
- [ ] `/forge/supervision` et `/forge/mosaique` mènent à la bonne densité.
- [ ] Aucun flux n'est ouvert en densité Aperçus (règle F-76 inchangée) ; la mosaïque garde son plafond
      de quatre flux (F-70) et son zoom.
- [ ] Aucune couleur hors `DESIGN_SYSTEM.md`.

---

## Périmètre

### Hors scope (explicite)

- Plus de quatre flux (plafond F-70 inchangé, cadrage §8).
- Toute évolution des tuiles de supervision ou de mosaïque.
- Une préférence de densité stockée côté gateway (le cadrage fixe `localStorage`).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| densité | `apercus` | `?densite=` › `localStorage cg_forge_densite` › `apercus` |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `densite` (URL) | Non | `apercus`, `flux` | autre ⇒ ignoré |
| `cg_forge_densite` (stockage) | Non | `apercus`, `flux` | autre ⇒ ignoré |

---

## Technique

### Endpoint(s)

Aucun. Lectures inchangées (`GET /api/terminals/live`, lectures de tour de la mosaïque).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `forge-voir/voir-travailler.component.*` (nouveau) + `forge-voir/forge-density.ts` (densité : lecture
  de l'URL, stockage tolérant aux pannes).
- `SupervisionComponent`, `MosaiqueComponent` — en-têtes de page retirés (fil d'Ariane, titre, retour,
  lien croisé).
- `PostesComponent` — un seul bouton.
- `app.routes.ts` — `forge/voir` ; `forge/supervision` et `forge/mosaique` deviennent des redirections.

### Préoccupations transversales

- **Navigation / routing : oui.** Composants impactés et vérifiés : `app.routes.ts` (`forge/voir`,
  redirections de `forge/supervision` et `forge/mosaique`, matcher `forge/:hostRef` qui réserve
  `voir`, `supervision`, `mosaique`) ; `PostesComponent` (bouton) ; `SupervisionComponent` et
  `MosaiqueComponent` (liens retirés ; « Aller à la Forge » inchangé) ; `ShellComponent.forgeActive`
  (`/forge/voir` reste dans la Forge). Test de non-régression par ancien chemin.
- Auth / Principal, tenant, plans / limites : non.

---

## Plan de test

### Tests unitaires (frontend)

- [ ] `forge-density.spec.ts` — densité effective (URL, stockage, repli), stockage indisponible ou
      illisible, écriture.
- [ ] `voir-travailler.component.spec.ts` — rend la supervision ou la mosaïque selon la densité ;
      sélecteur ⇒ stockage + navigation `replaceUrl` ; `?densite=flux` enregistré ; retour à la Forge.
- [ ] `app.routes.spec.ts` — `/forge/supervision` ⇒ `/forge/voir?densite=apercus`, `/forge/mosaique` ⇒
      `/forge/voir?densite=flux` (routeur réel), `/forge/voir` résout le bon écran.
- [ ] `PostesComponent` — un seul bouton « Voir travailler » vers `/forge/voir`, plus de « Mosaïque ».
- [ ] `SupervisionComponent`, `MosaiqueComponent` — suites existantes vertes.

### Isolation workspace

- [x] Non applicable — aucune donnée nouvelle.

---

## Dépendances

### Subfeatures bloquantes

- SF-98-01 — `done` (bandeau de la Forge).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Un écran conteneur**, pas une fusion des deux composants : leur logique (aucun flux d'un côté,
  quatre lectures de tour de l'autre) reste séparée et testée telle quelle ; seule la porte est unique.
- **`replaceUrl` au changement de densité** : la densité est une manière de regarder, pas une page ;
  « retour » doit ramener d'où l'on vient.
- **Clé de stockage `cg_forge_densite`**, sur le modèle des préférences locales existantes (`cg_*`) :
  « retenu par utilisateur » au sens du navigateur de l'utilisateur, comme le guide de l'Atelier.
