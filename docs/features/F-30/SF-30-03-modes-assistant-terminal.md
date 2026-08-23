# Mini-spec — [F-30 / SF-03] Modes « Assistant » et « Terminal »

---

## Identifiant

`F-30 / SF-03`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`ready`

## Date de création

2026-08-24

## Branche Git

`feat/SF-30-03-modes-assistant-terminal`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Renommer les deux modes de l'Atelier — « Édition » → **Assistant**, « Exécution » → **Terminal** — et
mettre le second en valeur comme capacité **Gold** (badge + accent orange de la charte).

---

## Contexte

« Édition » et « Exécution » sont des termes d'implémentation : ils décrivent ce que fait le backend,
pas ce que l'utilisateur obtient. **Assistant** et **Terminal** disent l'usage. Le second est la
capacité la plus coûteuse (sandbox facturé à l'heure) et la plus différenciante : elle mérite d'être
identifiée comme telle plutôt que présentée à égalité avec l'autre.

Les libellés apparaissent aussi dans les **messages d'erreur** (« Le mode Exécution est momentanément
indisponible ») : les renommer à moitié serait pire que ne rien renommer.

---

## Comportement attendu

### Cas nominal

1. Le sélecteur affiche **Assistant** (icône `edit_note`) et **Terminal** (icône `terminal`).
2. Le mode **Terminal** porte un badge **Gold** discret, en accent orange de la charte.
3. Le mode actif reste visuellement distinct (comportement `mat-button-toggle` inchangé).
4. Les phrases d'aide sous le sélecteur reprennent les nouveaux noms.
5. Les messages d'erreur utilisateur reprennent les nouveaux noms.
6. Le **comportement** des deux modes est strictement inchangé : mêmes flux, mêmes endpoints.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `forbidden` (non-Gold) sur un run Terminal | Message inchangé dans sa logique, reformulé : « Le mode Terminal est réservé à l'offre Gold. » |
| `agent_disabled` (flag coupé) | « Le mode Terminal est momentanément indisponible. » |
| Bascule pendant un envoi | Inchangé : la bascule reste refusée tant que `submitting()` est vrai |

---

## Critères d'acceptation

- [ ] Le sélecteur affiche « Assistant » et « Terminal » ; plus aucune occurrence de « Édition » / « Exécution » **visible par l'utilisateur**
- [ ] Le mode Terminal porte un badge « Gold » en accent orange (`--cg-accent`)
- [ ] Les messages d'erreur du flux d'exécution reprennent « Terminal »
- [ ] Les valeurs techniques (`'edit'` / `'exec'`), les endpoints et les flux sont **inchangés**
- [ ] Le mode Assistant (Phase 1) et le mode Terminal (Phase 2) se comportent exactement comme avant
- [ ] Aucune couleur ni police hors `DESIGN_SYSTEM.md`
- [ ] Aucun endpoint, aucune table, aucune migration

---

## Périmètre

### Hors scope

- Session persistante → SF-30-04
- Compteur de tokens → SF-30-05
- Modification du **gating** lui-même : l'Atelier entier est déjà réservé à Gold (SF-28-06). Le badge
  **signale** la capacité, il n'ajoute aucune règle d'accès.
- Renommage des identifiants techniques (`AtelierAgentMode`, `'edit'`/`'exec'`, `sendExec`) : un
  renommage de façade ne justifie pas de toucher au code qui marche.

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Libellés | « Assistant » / « Terminal » exactement |
| Badge | Texte « Gold », accent orange `--cg-accent`, taille réduite, non cliquable |
| Valeurs de mode | `'edit'` / `'exec'` inchangées (aucune migration d'état) |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/atelier.component.html` | Libellés du sélecteur, badge Gold, phrases d'aide |
| `atelier/atelier.component.ts` | Messages d'erreur (`mapAgentError`), commentaires de doc |
| `atelier/atelier.component.scss` | Style du badge Gold |
| `atelier/atelier.component.spec.ts` | Assertions de libellés / messages |

---

## Plan de test

### Tests unitaires (frontend)

- [ ] `mapAgentError('forbidden')` mentionne « Terminal » et « Gold »
- [ ] `mapAgentError('agent_disabled')` mentionne « Terminal »
- [ ] Le rendu affiche « Assistant » et « Terminal », et plus « Édition » / « Exécution »
- [ ] La bascule de mode reste refusée pendant un envoi (non-régression)
- [ ] Le mode par défaut reste `'edit'` et continue d'appeler `streamChat` (non-régression)

### Tests d'intégration

Sans objet : aucun appel réseau modifié.

### Isolation utilisateur

- [ ] **Non applicable** — aucun accès aux données, aucune règle d'accès ajoutée ou modifiée.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement. |
| Contexte tenant | **Non** | Aucun accès aux données. |
| Plans / limites | **Non** | Le badge est **informatif** : il n'ajoute ni gate ni appel de quota. Le gating Gold existant (`accessDenied` côté écran, `hasAccess` côté backend) est inchangé. |
| Navigation / routing | **Non** | Aucune route. |

---

## Dépendances

- Aucune. Indépendante de SF-30-02 (déjà mergée).

---

## Notes et décisions

- **Renommage de façade uniquement** : les valeurs `'edit'`/`'exec'` et les noms de méthodes restent.
  Renommer le code au passage augmenterait la surface de régression sans bénéfice utilisateur.
- **Badge informatif, pas un gate** : l'Atelier est déjà entièrement réservé à Gold. Le badge dit
  quelle capacité justifie l'offre, il ne restreint rien de plus.
