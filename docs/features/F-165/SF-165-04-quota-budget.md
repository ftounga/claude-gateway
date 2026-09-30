# Mini-spec — F-165 / SF-165-04 — `/quota` + `/budget` : consommation et budget

## Identifiant

`F-165 / SF-165-04`

## Feature parente

`F-165` — Commandes slash dans le terminal (vues et actions, à la sauce claude-gateway)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-165-04-quota-budget`

---

## Objectif

> En une phrase : ajouter deux commandes slash **vues** — **`/quota`** (consommation de tokens du plan
> vs plafond, restant, date de reset) et **`/budget`** (budget hebdomadaire du poste, s'il est lisible) —
> **sans aucun tour modèle**, en **réutilisant les services existants** (`UsageService` /api/usage isolé
> `user_id` ; `WeeklyBudgetService` partagé, admin), sans nouvel endpoint ni table.

---

## Comportement attendu

### Cas nominal — `/quota`

1. Taper `/quota` et valider : interception AVANT tout `send` (`parsePanelCommand`), **aucun tour**.
2. Le dispatch appelle **`GET /api/usage`** (`UsageService.getUsage()`, existant F-10, isolé `user_id` par
   le JWT) et rend un panneau `panelKind: 'quota'`, d'abord en **chargement**.
3. À la réponse, le panneau affiche la **consommation du plan** :
   - **tokens facturés** utilisés vs **plafond** du plan (jauge + %) ;
   - **tokens restants** ;
   - **reset** : date de fin de période (`periodEnd`) ;
   - période courante (`periodStart` → `periodEnd`).

### Cas nominal — `/budget`

1. Taper `/budget` et valider : interception AVANT tout `send`, **aucun tour**.
2. Le corps `/budget` (`AtelierSlashBudgetComponent`) **réutilise le service partagé**
   `WeeklyBudgetService` (la **même** lecture unique que la Forge et `/cout`, pour ne jamais afficher deux
   chiffres différents au même instant) : `load()` idempotent + `clientOf(hostId)` réactif.
3. Rendu :
   - **budget lisible** (poste avec plafond, appelant autorisé) → **budget hebdo**, **dépensé**,
     **restant**, part consommée (jauge + %) ;
   - **non lisible** (appelant non-admin, ou aucun plafond défini pour ce poste) → message clair et neutre
     (« Budget non disponible — visible par les administrateurs, ou aucun plafond défini pour ce poste »),
     **jamais** le budget d'un autre.

### Décisions de câblage (aucune nouvelle route, aucune table)

- `/quota` = **quota de tokens du plan** (`/api/usage`, F-10), la seule mesure de « consommation vs
  plafond » disponible **par utilisateur** et **non-admin**. Le **plafond par tour**
  `APP_ATELIER_MAX_TURN_TOKENS` (garde-fou de sécurité par message, F-70) n'est **exposé par aucun
  endpoint** : il n'est **pas** surfacé (le surfacer exigerait une nouvelle route hors périmètre) — voir
  §Notes.
- `/budget` = **budget hebdomadaire en euros** (`WeeklyBudgetService`, F-133/F-70), **scopé admin** côté
  passerelle (`/admin/cost/summary` → 403 pour le non-admin, que le service partagé traduit en `null`).
  Décision du point ouvert du cadrage §7 : **option (b)** — `/budget` reste réservé à qui a déjà le droit
  de lire le budget ; pour les autres, **dégradation propre** (message clair), **sans** exposer le budget
  d'autrui, **sans** nouvel endpoint non-admin (hors périmètre de cette SF).

### Cas d'erreur / bord

| Situation | Comportement attendu |
|-----------|----------------------|
| `/quota` : gateway muette / erreur réseau | Panneau en **échec** neutre (« quota indisponible ») ; aucun tour |
| `/budget` : appelant non-admin | Message **dégradé** clair ; aucun budget d'autrui exposé (service → `null`) |
| `/budget` : aucun plafond défini pour ce poste | Même message dégradé neutre (pas de plafond inventé) |
| `/budget` : aucun poste (`hostId` absent) | Message neutre « pas de poste » ; aucun appel budget |
| Terminal en **lecture seule** (mosaïque, F-83) | Pas de composer → commandes impossibles (inchangé SF-165-01) |

---

## Critères d'acceptation

- [ ] `/quota` et `/budget` figurent au registre `SLASH_PANEL_COMMANDS` (famille **Vue**, `panelKind`
      `quota` / `budget`) et apparaissent dans l'autocomplétion et dans `/aide`.
- [ ] Valider `/quota` ou `/budget` **n'émet jamais `send`** (garantie « aucun tour ») — prouvé par un test.
- [ ] `/quota` appelle **`GET /api/usage`** et rend used/quota/remaining + reset — prouvé par un test.
- [ ] `/budget` **réutilise `WeeklyBudgetService`** (pas de nouvelle lecture concurrente) et **dégrade
      proprement** quand le budget n'est pas lisible (message clair, jamais le budget d'autrui) — prouvé
      par un test.
- [ ] **Aucun nouvel endpoint, aucune table, aucune migration** : réutilisation stricte de l'existant.
- [ ] **Isolation** : `/quota` isolé `user_id` (JWT) par l'endpoint existant ; `/budget` ne montre le
      budget que si l'appelant y a déjà droit (service partagé → `null` sinon).
- [ ] Les panneaux se **ferment** (bouton fermer, SF-165-01) et restent **hors** `displayedMessages`.
- [ ] **Design** : jetons `--cg-*` uniquement ; `tabular-nums` ; cibles ≥ 44 px ; aucun débordement à
      390 px (SF-158) ; aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] **Non-régression** : `/aide`, `/cout`, `/contexte`, `/revue` (macro F-121), message ordinaire,
      autocomplétion `@`, dictée, steer, porte, `demander` restent intacts.
- [ ] Builds **verts** : `npm run build` + Karma ciblé front. (Backend : aucun changement.)

---

## Périmètre

### Hors scope (explicite)

- Les autres commandes `/poste`, `/sujet`, `/compacter`, `/nouveau`, `/rappel` (SF-165-05 → 06).
- Un **endpoint budget non-admin par projet** : reporté/écarté ici (décision option (b), cadrage §7).
- Le **plafond par tour** `APP_ATELIER_MAX_TURN_TOKENS` (aucun endpoint de lecture ; hors périmètre).
- Toute **nouvelle table**, migration ou endpoint (réutilisation stricte de l'existant).
- Toute logique de **moteur IA**.

---

## Technique

### Endpoint(s)

**Aucun endpoint créé.** Réutilisation en lecture :

| Méthode | Route (existante) | Service front | Isolation |
|---------|-------------------|---------------|-----------|
| `GET` | `/api/usage` (F-10) | `UsageService.getUsage()` | `user_id` (JWT) |
| `GET` | `/api/admin/cost/summary?period=week` (F-133) | `WeeklyBudgetService` (partagé) | **admin** (403 sinon → `null`) |

### Tables impactées

Aucune. **Aucune migration Liquibase.**

### Composants / fichiers

**Frontend**
| Fichier | Rôle |
|---------|------|
| `atelier/terminal/slash-panel-commands.ts` (modifié) | Entrées registre `/quota` + `/budget` ; type `QuotaPanelState` + `ThreadQuotaSummary` ; `SlashPanel.quota`/`quotaState` ; `buildPanel` (quota → chargement, budget → réactif) |
| `atelier/terminal/atelier-slash-quota.component.ts` (+ html/scss, nouveau) | Corps `/quota` (jauge, restant, reset) — présentation pure |
| `atelier/terminal/atelier-slash-budget.component.ts` (+ html/scss, nouveau) | Corps `/budget` — **réutilise `WeeklyBudgetService`** (réactif), dégrade proprement |
| `atelier/terminal/atelier-terminal.component.ts` (modifié) | Dispatch `/quota` (GET usage) ; `/budget` (panneau réactif, aucun état à charger) ; imports |
| `atelier/terminal/atelier-terminal.component.html` (modifié) | `@case ('quota')` + `@case ('budget')` |

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Frontend (Karma ciblé)

- [ ] `slash-panel-commands.spec.ts` (ajouts) : `/quota` + `/budget` au registre (famille Vue,
      `panelKind` `quota`/`budget`), suggestions au préfixe, interception, `buildPanel`.
- [ ] `atelier-slash-quota.component.spec.ts` (nouveau) : chargement/échec/prêt ; jauge used/quota ;
      restant ; reset rendu.
- [ ] `atelier-slash-budget.component.spec.ts` (nouveau) : budget lisible → montants ; non lisible
      (`clientOf` → `null`) → **message dégradé**, aucun montant d'autrui.
- [ ] `atelier-terminal.component.spec.ts` (ajouts) : dispatcher `/quota` **n'émet pas `send`**, un
      `GET /api/usage`, panneau `ready` ; `/quota` sans réseau → `error` ; `/budget` **n'émet pas `send`**
      et rend le corps budget (dégradé quand aucun budget).

### Tests d'intégration / backend

- [x] **Aucun** — SF-165-04 n'ajoute aucun endpoint. Les routes réutilisées (`/api/usage`,
  `/api/admin/cost/summary`) sont déjà couvertes par leurs tests d'intégration existants (F-10, F-133),
  isolation comprise.

### Isolation utilisateur

- [x] Applicable — portée par les endpoints existants : `/api/usage` isolé `user_id` (JWT) ;
  `/api/admin/cost/*` **scopé admin** (403 → `null`). **Aucune nouvelle route** n'est introduite ; `/budget`
  **dégrade** pour le non-admin et n'expose jamais le budget d'autrui (couvert par un test front).

---

## Préoccupations transversales

| Préoccupation | Impact | Composants vérifiés / listés |
|--------------|--------|------------------------------|
| Auth / Principal | Aucun changement. Les lectures s'appuient sur le JWT existant (interceptor) | `UsageService`, `AdminCostService` (inchangés) |
| Contexte tenant | **Aucun nouvel accès données** : réutilisation des endpoints existants (usage isolé `user_id`, budget admin) | `UsageService.getUsage`, `WeeklyBudgetService.clientOf` |
| Plans / limites | `/quota` **lit** le quota du plan (F-10) sans le modifier ; aucun nouveau gate ; commandes **gratuites** (aucun tour) | `submit()` (interception avant gate, inchangé SF-165-01) |
| Navigation / routing | Aucune route Angular ajoutée/modifiée (panneaux locaux dans le fil) | — |

---

## Dépendances

### Subfeatures bloquantes

- **SF-165-01** (socle) — **livrée**. **SF-165-02/03** (patrons de panneaux de lecture) — **livrées**.

### Questions ouvertes impactées

- **Cadrage §7** (budget non-admin par projet) : **tranché ici en option (b)** — `/budget` réservé à qui a
  déjà le droit ; dégradation propre pour les autres ; **pas** de nouvel endpoint non-admin. Aucune entrée
  de `docs/OPEN_QUESTIONS.md` n'est ouverte par ce choix.

---

## Notes et décisions

- **`/quota` = quota du plan (tokens).** `GET /api/usage` (F-10) est la mesure « consommation vs plafond,
  restant, reset » **par utilisateur** et **accessible au non-admin**. Le **plafond par tour**
  `APP_ATELIER_MAX_TURN_TOKENS` est un **garde-fou de sécurité par message**, porté par une config sans
  endpoint de lecture : le surfacer imposerait une nouvelle route, hors du périmètre « réutiliser
  l'existant » de cette SF. Décision : **ne pas** l'exposer ici.
- **`/budget` réutilise le service PARTAGÉ `WeeklyBudgetService`** (et non une nouvelle lecture) : c'est la
  raison d'être de ce service (F-133/SF-133-15) — « une seule lecture pour tous les écrans », sinon la Forge
  et le terminal afficheraient deux chiffres différents. Le budget est **scopé admin** côté passerelle ;
  pour le non-admin la réponse est `null` et le panneau **dégrade** (message clair) — **jamais** le budget
  d'autrui. C'est l'**option (b)** du point ouvert du cadrage §7.
- **Aucun changement backend** : SF-165-04 est **100 % frontend**, réutilisation stricte d'endpoints déjà
  testés (isolation comprise).
- **Garantie « aucun tour »** conservée : `dispatchPanelCommand` n'émet jamais `send.emit()` ; `/quota` =
  un GET de lecture, `/budget` = lecture d'un service partagé — rendus locaux, hors `displayedMessages`.
- **Gateway-First / Provider-First** : des vues sur NOS données (usage, budget) ; aucune capacité de Claude
  réimplémentée ; aucun code métier dépendant d'Anthropic.
- **Aucune incohérence `ARCHITECTURE_CANONIQUE.md`** : aucune table, aucun endpoint.
