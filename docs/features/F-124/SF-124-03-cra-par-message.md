# Mini-spec — F-124 / SF-124-03 — CRA par message

## Identifiant

`F-124 / SF-124-03`

## Feature parente

`F-124` — Suivi d'activité et de revenu par client (TJM, cumul, CRA)

## Statut

`ready`

## Date de création

2026-09-16

## Branche Git

`feat/SF-124-03-cra-par-message`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Permettre à l'utilisateur de déclarer son CRA en **un message en langage naturel** (« Free 20j, KG
13j ») : le **modèle extrait** `{poste → jours, mois}`, la Gateway rapproche, valide, persiste (écrase
pour un mois) et **récapitule ce qu'elle a compris**.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur ouvre le point d'entrée **« CRA »** de la Forge (dialogue depuis le bandeau) et écrit
   un message libre.
2. `POST /activity/cra {message}` → la Gateway appelle le **provider IA** (interface `AIProvider`,
   Provider-First) qui renvoie une liste `[{client, days, month}]` (mois **courant** par défaut, sauf
   précision dans le message).
3. La Gateway **rapproche** chaque nom cité à un **poste** du user (par nom/alias, insensible à la
   casse/accents) et **valide** : poste connu, mois `YYYY-MM` valide, `0 < jours ≤ jours ouvrés du
   mois`, demi-journées (0,5) admises.
4. Les entrées valides sont **persistées** dans `cra_entries` (**écrase** l'entrée du même
   `(poste, mois)`).
5. La réponse **récapitule** chaque ligne : poste reconnu + jours + mois + statut (`écrit` /
   `refusé` avec motif / `nom inconnu` à préciser). Le cumul (SF-124-02) est relu ensuite.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Nom de client **non reconnu** | Ligne `unknown` dans le récap, **demandée**, jamais devinée ; rien écrit pour elle | 200 (récap) |
| Jours **> jours ouvrés** du mois | Ligne `rejected` (motif), rien écrit pour elle | 200 (récap) |
| Jours ≤ 0 | Ligne `rejected` | 200 (récap) |
| Message vide | Refus | 400 |
| Le provider ne renvoie rien d'exploitable | Récap vide + message « rien compris », rien écrit | 200 |
| Fournisseur indisponible | Erreur provider (relayée) | 502/503 |
| Non authentifié / sans droit Forge | Refus | 401 / 403 |

---

## Critères d'acceptation

- [ ] Un message « Free 20j, KG 13j » (postes `Free` et `KG` existants) écrit **deux** entrées `cra_entries` pour le **mois courant**, et le récap les liste `écrit`.
- [ ] Une précision de mois dans le message (« mon CRA de septembre… ») vise **ce** mois, pas le courant.
- [ ] Renvoyer un CRA pour un mois **écrase** l'entrée existante (pas de doublon).
- [ ] Un nom **non reconnu** n'est **jamais** deviné : ligne `unknown`, rien écrit.
- [ ] Jours **> jours ouvrés** du mois → ligne `rejected`, rien écrit ; **0,5** accepté.
- [ ] L'extraction passe par l'interface **`AIProvider`** (aucune dépendance directe à Anthropic).
- [ ] Isolation `user_id` (+ `host_id`) : un message n'écrit jamais sur le poste d'un autre ; le rapprochement ne voit que les postes du user courant.

---

## Périmètre

### Hors scope (explicite)

- Modifier le **calcul** du cumul (fait en SF-124-02) — cette SF n'écrit que `cra_entries`.
- Toute facturation ; un historique/journal des CRA ; l'édition ligne à ligne dans un tableau.
- Réimplémenter un NLP maison (Provider-First : le modèle extrait, la Gateway valide/persiste).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `cra_entries.days` | — | `0 < days ≤ jours ouvrés du mois`, pas de 0,25 (multiples de 0,5) |
| mois visé | mois courant | sauf précision extraite du message |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Normalisation |
|-------|-------------|------------------|---------------|
| `message` | Oui | non vide, borné (ex. 4000 car.) | trim |
| nom → poste | — | rapproché par nom/alias, insensible casse/accents ; **inconnu → demandé** | — |
| `month` | — | `YYYY-MM` ; défaut mois courant | — |
| `days` | — | `0 < d ≤ ouvrés(mois)`, pas de fraction hors 0,5 | arrondi 1 décimale |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Droit |
|---------|-----|------|-------|
| POST | `/activity/cra` | JWT | Forge (`requireRunnerAccess`) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `cra_entries` (SF-124-02) | INSERT / UPDATE (écrase) | déclaré par `(user_id, host_id, year_month)` |
| `runner_hosts` | SELECT (rapprochement nom→poste, filtré `user_id`) | — |

### Migration Liquibase

- [ ] Non applicable — `cra_entries` existe déjà (migration `110`, SF-124-02).

### Composants Angular

- `core/services/poste-billing.service.ts` — `submitCra(message)` (`POST /api/activity/cra`).
- `postes/cra-dialog/` — dialogue « CRA » (textarea + récap des lignes comprises), ouvert depuis le
  bandeau de la Forge (`postes.component`). Charte, aucune couleur nouvelle.

---

## Plan de test

### Tests unitaires (backend)

- [ ] `CraService` — extraction (provider **mocké**) → entrées écrites ; rapprochement nom→poste ;
  nom inconnu **demandé** ; jours > ouvrés **refusé** ; 0,5 accepté ; écrasement d'un mois ;
  mois courant par défaut vs mois précisé.
- [ ] Le prompt/parse tolère un JSON du modèle (liste vide → récap vide).

### Tests d'intégration (backend)

- [ ] `POST /activity/cra` (provider mocké) → 200, entrées persistées, récap correct.
- [ ] Message vide → 400.
- [ ] Isolation : le message d'un user n'écrit jamais sur le poste d'un autre (nom identique → inconnu).

### Isolation user_id

- [x] Applicable — rapprochement et persistance filtrés `user_id` (+ `host_id`) ; testé.

### Tests frontend

- [ ] `poste-billing.service.spec.ts` — `submitCra` émis et mappé.
- [ ] `cra-dialog` — saisie, envoi, affichage du récap (écrit / refusé / inconnu).

---

## Préoccupations transversales — analyse d'impact

- **Auth / Principal** : endpoint sous la chaîne JWT existante, identité via `CurrentUser`. Composant :
  `CraController` (nouveau). Aucun impact sur les endpoints existants.
- **Contexte tenant** : rapprochement nom→poste **limité aux postes du user** (`RunnerHostService.list`
  / `requireOwned`) ; persistance filtrée `(user_id, host_id)`. Composants : `CraService`,
  `CraEntryRepository`, `RunnerHostService`.
- **Plans / limites** : le tour d'extraction **consomme un appel fournisseur** — comme les autres tours
  d'agent (quota existant). Pas de nouveau gate ; un seul appel par message.
- **Provider-First / Provider Independence** : l'extraction passe par `AIProvider` (jamais Anthropic en
  direct). La Gateway ne réimplémente pas de NLP ; elle valide et persiste.
- **Navigation / routing** : **aucune route/guard ajoutés** — un `MatDialog` ouvert depuis le bandeau
  de la Forge. Les 4 terminaux ne sont pas touchés.

---

## Notes et décisions

- **Emplacement du CRA** : un **dialogue** (`MatDialog`) ouvert depuis le bandeau de la Forge, et non un
  nouveau terminal ni un onglet de poste — un message CRA cite **plusieurs** clients, il est donc
  **au niveau Forge**, pas au niveau d'un poste. Cohérent avec les gestes du bandeau (F-98) ; aucun des
  4 terminaux n'est un CRA.
- **Extraction par le modèle** (Provider-First) : consigne système demandant un JSON strict
  `[{client, days, month}]` ; la Gateway parse tolérant, rapproche, valide, persiste. Un nom inconnu est
  **rendu tel quel** dans le récap (« précisez le poste »), jamais rapproché au hasard.
- **Mois par défaut** = mois courant ; une précision dans le message (« de septembre ») l'emporte.
