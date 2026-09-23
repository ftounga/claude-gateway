# Mini-spec — F-148 / SF-148-05 — Injecter le contenu borné de `STATE.md` / `PLAN-ACTION.md` du sujet courant

## Identifiant

`F-148 / SF-148-05`

## Feature parente

`F-148` — Performance du raisonnement (affinages)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-148-05-injecter-state-plan`

---

## Objectif

> En une phrase : ajouter au préfixe de la consigne système un bloc « état courant du sujet » **borné**
> — le contenu de `STATE.md` et `PLAN-ACTION.md` du **sujet (projet) courant** — pour que l'agent
> reprenne le fil sans un tour `read_file` d'amorçage, sans casser le cache de prompt et sans
> réinjecter ce que F-136/F-137 mettent déjà dans le contexte.

---

## Constat / existant — vérifié avant dev (anti-doublon)

- **F-136 `HostMapOutline`** (`HostMapOutline.java`) injecte dans le **préfixe** uniquement des
  **titres** (chemin + titres de sections) des fichiers de carte **de genre `MAP`** posés à la
  **racine du poste** (`README.md`, `acces.md`, `reseau.md`, `plateformes.md`, `donnees.md`,
  `exploitation.md`). **Pas de contenu**, et **pas** `STATE.md`/`PLAN-ACTION.md`.
- **F-137 `HostFactLookup.factsFor`** injecte des **faits filtrés par la question** dans le **dernier
  message** du tour (`AtelierChatService.java:1242`), **jamais dans le préfixe** — c'est volatil par
  construction.
- **`HostMapStore`** ne range en base que le **contenu des fichiers `MAP`** (racine) ; `STATE.md` et
  `PLAN-ACTION.md` sont de genre **`TEMPLATE`** (`GovernancePackageSeeder`), **par sujet**, et ne sont
  **pas** rangés dans `HostMapStore`. **La prémisse du cadrage (« contenu déjà en base HostMapStore »)
  est donc inexacte** pour ces deux fichiers — voir §Notes/décisions.

**Conclusion anti-doublon** : le contenu de `STATE.md`/`PLAN-ACTION.md` du sujet courant n'est
aujourd'hui **nulle part dans le contexte** (ni préfixe via F-136, ni message via F-137). L'injecter
n'est donc pas une redondance.

---

## Comportement attendu

### Cas nominal

- Sur un **projet-sujet** (workspace du tour qui n'est pas le terminal du poste), après le bloc de
  conventions/gouvernance, ajouter un bloc « état courant du sujet » contenant le contenu de
  `STATE.md` puis `PLAN-ACTION.md` du sujet, **borné par fichier** (`SUBJECT_STATE_MAX_CHARS`), chacun
  sous son chemin. Fichier absent → simplement omis. Aucun fichier présent → aucun bloc.
- Le contenu est lu **là où les fichiers vivent** (même chemin d'accès `readOptional` que le `CLAUDE.md`
  déjà lu au tour, donc **target-aware** : runner ou hébergé) et injecté **verbatim** (borné). À contenu
  stable, octets stables → le préfixe **ne change que lorsque l'état change réellement** (même propriété
  que le `CLAUDE.md` injecté juste au-dessus).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `STATE.md` / `PLAN-ACTION.md` absent ou illisible | Fichier omis, jamais bloquant (repli passant, comme `CLAUDE.md`) |
| Terminal du poste (pas de sujet courant) | Aucun bloc injecté |
| Contenu trop long | Tronqué à `SUBJECT_STATE_MAX_CHARS` + mention de coupe ; borne globale `SYSTEM_MAX_CHARS` conservée |
| Machine muette / lecture en panne | Bloc omis, tour non raté |

---

## Critères d'acceptation

- [ ] Sujet avec `STATE.md`/`PLAN-ACTION.md` présents → le bloc « état courant du sujet » apparaît dans
      la consigne avec leur contenu, sous leur chemin.
- [ ] Fichiers absents → aucun bloc (consigne inchangée par rapport à avant SF-148-05).
- [ ] Contenu > borne → tronqué avec mention de coupe.
- [ ] **Stabilité (cache)** : à contenu identique, la consigne système est **byte-identique** d'un tour
      au suivant (aucune volatilité introduite).
- [ ] **Isolation** : les lectures passent par `(userId, workspaceId)` du tour (`readOptional`), jamais
      un autre compte / projet.
- [ ] Terminal du poste → aucun bloc.

---

## Périmètre

### Hors scope (explicite)

- Ranger `STATE.md`/`PLAN-ACTION.md` en base (cache gateway) : c'est le rôle de **SF-148-06** (cache
  `(host, digest)` de `CLAUDE.md` + skills) ; ces deux fichiers pourront y être ajoutés le jour venu.
- Toute lecture ou réinjection des **faits** (F-137) ou des **titres** (F-136) — inchangés.
- Aucun changement d'infra, aucun composant cluster, **aucune migration** (aucun nouveau stockage).

---

## Contraintes de validation

| Champ | Valeur | Règle |
|-------|--------|-------|
| Fichiers injectés | `STATE.md`, `PLAN-ACTION.md` | Du sujet (workspace) courant, dans cet ordre |
| Borne par fichier | `SUBJECT_STATE_MAX_CHARS` (6 000) | Coupe + mention `… (état tronqué)` |
| Borne globale | `SYSTEM_MAX_CHARS` (40 000) | Coupe finale existante conservée |

---

## Technique

### Composants impactés

| Composant | Opération |
|-----------|-----------|
| `AtelierChatService.buildSystemPrompt` | Après le bloc de gouvernance, injecte le contenu borné de `STATE.md`/`PLAN-ACTION.md` du sujet, via `readOptional` (target-aware), hors terminal du poste ; compteurs d'amorçage incrémentés comme pour `CLAUDE.md` |

### Endpoint(s) / Tables / Migration

Aucun endpoint, aucune table, **aucune migration Liquibase** (aucun nouveau stockage : lecture des
fichiers existants du sujet).

### Composants Angular

Aucun.

---

## Préoccupations transversales

- **Auth / Principal** : inchangé.
- **Contexte tenant** : les lectures passent par `readOptional(userId, workspace, path)`, déjà scoppé
  `(userId, workspaceId)` (mêmes appels que `CLAUDE.md`). Aucun nouveau moyen de résoudre le tenant.
- **Plans / limites** : non touché.
- **Navigation / routing** : non touché.

---

## Plan de test

### Tests unitaires / composition de consigne

- [ ] `STATE.md`/`PLAN-ACTION.md` présents → bloc injecté avec leur contenu (via requête reçue par le
      fournisseur stub).
- [ ] Absents → aucun bloc.
- [ ] Contenu trop long → tronqué + mention.
- [ ] Stabilité : deux tours identiques → même consigne (byte-identique).
- [ ] Isolation : `readOptional` appelé avec `(userId, workspaceId, "STATE.md")`.
- [ ] Terminal du poste → aucun bloc.

### Tests d'intégration

- Non applicable : composition de consigne, aucun endpoint.

### Isolation workspace / tenant

- [x] Testée : lectures scoppées au couple `(userId, workspaceId)` du tour.

---

## Dépendances

### Subfeatures bloquantes

- Aucune.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Écart documenté par rapport au cadrage** : le cadrage supposait le contenu de
  `STATE.md`/`PLAN-ACTION.md` « déjà en base (HostMapStore), pas de lecture runner ». **Vérification
  faite** : `HostMapStore` ne range que les fichiers de genre `MAP` (racine du poste) ; `STATE.md` et
  `PLAN-ACTION.md` sont de genre `TEMPLATE`, **par sujet**, et **absents** de la base — les injecter
  depuis la base exigerait un **nouveau stockage** (donc une migration + une modification invasive de
  `HostMapStore.refresh`/`filesOf` qui régresserait `HostMapOutline`). Décision : les injecter via le
  **même `readOptional` target-aware déjà utilisé pour `CLAUDE.md`** au même endroit de
  `buildSystemPrompt`. C'est **exactement le même profil de coût** qu'une lecture déjà payée chaque
  tour (`CLAUDE.md`, arborescence, skills), et **SF-148-06** — qui met précisément en cache
  `CLAUDE.md` + skills par `(host, digest)` — est l'endroit prévu pour y adjoindre ces deux fichiers
  sans lecture runner. Ainsi : **aucune migration**, aucun nouveau composant, périmètre minimal.
- **Cache de prompt (F-134) préservé** : le bloc est injecté **verbatim** dans le préfixe stable. À
  contenu identique → octets identiques → cache tenu ; le préfixe ne change **que** lorsque l'état
  change réellement — c'est la discipline « ré-injecté seulement au changement de digest » demandée par
  le cadrage, réalisée par construction (le contenu **est** le digest), et **strictement identique** au
  traitement déjà appliqué au `CLAUDE.md` injecté juste au-dessus.
- **Gateway-First / Provider-First** : aucune logique de moteur IA ; on compose un préfixe à partir de
  fichiers du projet, comme pour `CLAUDE.md`.
