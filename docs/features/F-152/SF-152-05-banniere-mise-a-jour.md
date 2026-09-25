# Mini-spec — F-152 / SF-152-05 — Bannière « nouvelle version disponible → recharger » (SwUpdate)

## Identifiant

`F-152 / SF-152-05`

## Feature parente

`F-152` — PWA installable (icône sur l'écran d'accueil)

## Statut

`ready`

## Date de création

2026-09-25

## Branche Git

`feat/SF-152-05-banniere-maj`

---

## Objectif

Afficher une **bannière discrète « Une nouvelle version est disponible »** avec un bouton
**« Recharger »** qui active la nouvelle version du service worker (F-152) et recharge la page —
pour qu'après un déploiement l'utilisateur n'ait **plus** à fermer/rouvrir l'app installée (PWA).

---

## Comportement attendu

### Cas nominal

- **Détection proactive** : au démarrage l'app appelle `swUpdate.checkForUpdate()`, puis
  **périodiquement** (toutes les **30 min**, `interval`) et **au retour de focus de l'onglet**
  (`visibilitychange`, quand `document.visibilityState === 'visible'`). Aucune fermeture de l'app
  n'est nécessaire pour repérer une nouvelle version.
- Quand le service worker a téléchargé et **préparé** une nouvelle version, il émet un événement
  `versionUpdates` de type **`VERSION_READY`** → la bannière s'affiche.
- **« Recharger »** → `swUpdate.activateUpdate()` puis `document.location.reload()` : la nouvelle
  version est appliquée sans manipulation manuelle. Bouton désactivé pendant l'opération
  (anti-double-clic).
- **« Plus tard »** → ferme la bannière (non intrusif). Elle réapparaîtra au prochain check si la
  version est toujours prête.
- **Placement global** : bannière montée dans `app.component` (racine toujours présente),
  **`position: fixed`** en bas — visible sur **mobile ET desktop**, sur **toutes les routes**
  (connecté ou non), **non bloquante** (ne pousse pas le contenu, n'empêche pas d'utiliser l'app).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `swUpdate.isEnabled` faux (dev / navigateur sans SW) | La garde retourne immédiatement : **aucun abonnement, aucun check, bannière jamais affichée, aucune erreur** |
| `checkForUpdate()` échoue (réseau) | Best-effort : l'échec est avalé (try/catch), aucune erreur ne parasite l'app, le prochain check retentera |
| `activateUpdate()` échoue | Le rechargement est **quand même** déclenché (un chargement neuf réamorce le SW) ; la garde anti-double-clic est levée par la recharge |

---

## Critères d'acceptation

- [ ] Sur un événement `versionUpdates` de type `VERSION_READY`, la bannière devient visible.
- [ ] Un événement `versionUpdates` d'un autre type (ex. `VERSION_DETECTED`, `NO_NEW_VERSION`) n'affiche **pas** la bannière.
- [ ] « Recharger » appelle `swUpdate.activateUpdate()` **puis** recharge la page (`document.location.reload()`).
- [ ] « Plus tard » masque la bannière.
- [ ] Si `swUpdate.isEnabled` est faux : aucun abonnement n'est pris, `checkForUpdate()` n'est jamais appelé, la bannière n'est jamais affichée, aucune erreur.
- [ ] Détection proactive : `checkForUpdate()` est appelé **au démarrage** et **au retour au premier plan** de l'onglet (`visibilitychange` → visible), et **périodiquement**.
- [ ] La bannière respecte `DESIGN_SYSTEM.md` : jetons `--cg-*` uniquement, cibles tactiles ≥ 44 px, feuille SCSS dédiée sous le budget (`anyComponentStyle` < 12 ko).
- [ ] `npm run build` (prod) **vert** ; suite de tests frontend ciblée **verte**.

---

## Périmètre

### Hors scope (explicite)

- Le service worker et sa config (`ngsw-config.json`, `provideServiceWorker`) → **SF-152-02** (livrée).
- Le contenu réel du cache / la stratégie de cache → **SF-152-02**.
- Toute notification système (Web Push, titre d'onglet) → **F-153** (livrée).
- Le déploiement de l'image `frontend` (effet visible seulement après déploiement) — **hors périmètre de dev, la consigne interdit de déployer**.

---

## Valeurs initiales

Aucune entité, aucune persistance. État de composant uniquement :

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `ready` (signal) | `false` | passe à `true` sur `VERSION_READY` |
| `reloading` (signal) | `false` | passe à `true` pendant `activateUpdate` + reload |

---

## Contraintes de validation

Aucun champ saisi par l'utilisateur (aucun formulaire). Réglages :

| Réglage | Valeur | Règle |
|---------|--------|-------|
| garde | `swUpdate.isEnabled` | si faux → rien (dev / SW absent) |
| filtre d'événement | `VERSION_READY` uniquement | les autres types ne montrent rien |
| période de check | 30 min | proactif, sans fermer l'app |
| check au focus | `visibilitychange` → `visible` | repère une version au retour sur l'onglet |

---

## Technique

### Endpoint(s) / Tables / Migration

**Aucun.** Pur frontend. Pas d'endpoint, pas de table, pas de migration, pas de composant cluster,
aucun impact backend ni cache F-134.

### Composants Angular

- `VersionUpdateBannerComponent` (`layout/version-update-banner/`) — s'abonne à
  `SwUpdate.versionUpdates` (filtre `VERSION_READY`), pilote la détection proactive
  (`checkForUpdate` au démarrage + `interval` 30 min + `visibilitychange`), affiche la bannière,
  active + recharge sur « Recharger », se masque sur « Plus tard ». Gardé par `swUpdate.isEnabled`.
- `AppComponent` — importe et rend `<app-version-update-banner />` au-dessus du `router-outlet`
  (placement global, toutes routes).

### Fichiers impactés

- `frontend/src/app/layout/version-update-banner/version-update-banner.component.ts` (nouveau)
- `frontend/src/app/layout/version-update-banner/version-update-banner.component.html` (nouveau)
- `frontend/src/app/layout/version-update-banner/version-update-banner.component.scss` (nouveau, feuille dédiée)
- `frontend/src/app/layout/version-update-banner/version-update-banner.component.spec.ts` (nouveau)
- `frontend/src/app/app.component.ts` — import + rendu de la bannière
- `frontend/src/app/app.component.html` — `<app-version-update-banner />`
- `frontend/src/app/app.component.spec.ts` — fournir `provideServiceWorker(..., { enabled: false })` (la bannière enfant injecte `SwUpdate`)

---

## Préoccupation transversale

| Préoccupation | Impacté ? | Composants impactés |
|---------------|-----------|---------------------|
| Auth / Principal | Non | aucun — la bannière ne dépend d'aucune session, ne lit aucun `user_id` |
| Contexte tenant | Non | aucun accès données |
| Plans / limites | Non | aucun appel aux services de limites |
| **Navigation / routing** | **Non** — aucune route ni guard ajouté/modifié. Le composant est rendu **hors** du `router-outlet` (racine), il n'introduit aucun chemin. Le seul effet de navigation possible est `document.location.reload()` (recharge de la page courante, pas une route Angular), déclenché **uniquement** par le geste explicite « Recharger ». Composants vérifiés : `app.component` (racine, rendu du composant), aucun autre. | `app.component` |

---

## Plan de test

### Tests unitaires (composant)

- [ ] `VERSION_READY` simulé (mock `SwUpdate` avec un `Subject` sur `versionUpdates`) → `visible()` vrai.
- [ ] Événement d'un autre type → `visible()` reste faux.
- [ ] « Recharger » → `activateUpdate()` appelé **puis** recharge appelée (recharge mockée), `reloading` vrai pendant l'opération.
- [ ] « Plus tard » → `visible()` repasse faux.
- [ ] `isEnabled = false` → aucun abonnement, `checkForUpdate` jamais appelé, `visible()` toujours faux.
- [ ] Détection proactive : `checkForUpdate` appelé **au démarrage** ; appelé de nouveau sur un `visibilitychange` (onglet visible).
- [ ] Non-régression : `app.component.spec.ts` vert (SW fourni désactivé → aucune bannière).

### Tests d'intégration

- [ ] `npm run build` (prod) **vert** (génère la coquille + `ngsw`, la bannière compile dans le bundle).

### Isolation workspace / user_id

- [x] **Non applicable** — configuration/UI runtime, aucun accès données, aucun `user_id`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-152-02` — service worker (`SwUpdate` disponible) — **Done**.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` inchangé).

---

## Notes et décisions

- **D-placement-app-component** : la bannière est montée dans `app.component` (racine), non dans le
  shell : un déploiement peut survenir sur n'importe quelle route (app installée), et la racine est
  la seule surface toujours montée. `position: fixed` en bas → non intrusive, ne pousse aucun layout.
- **D-VERSION_READY-seul** : on n'affiche la bannière que sur `VERSION_READY` (version **prête**),
  jamais sur `VERSION_DETECTED` (téléchargement en cours) — recharger avant que la version soit
  prête n'appliquerait rien.
- **D-recharge-isolée** : `document.location.reload()` est isolée dans une méthode protégée
  (`doReload`) pour être mockée en test — même patron que `redirect()` de `QuotaAlertBannerComponent`.
- **D-garde-isEnabled** : la garde `swUpdate.isEnabled` (comme `PushActivationService`,
  SF-153-03) coupe tout en dev/test : le SW n'y est ni généré ni enregistré, la bannière n'existe
  pas.
- **Charte** : bannière « info » = fond navy `--cg-primary` (§5 Notifications), texte clair,
  bouton d'action « Recharger » orange (`mat-flat-button color="primary"`), « Plus tard » en bouton
  texte encre claire. Cibles tactiles ≥ 44 px. Feuille SCSS dédiée du composant, bien sous le budget.
