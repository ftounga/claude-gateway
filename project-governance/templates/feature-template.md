# Feature — [FEAT-XX] Titre de la feature

> Template : copier ce fichier, renommer en `FEAT-XX-nom-feature.md`, placer dans `docs/features/`

---

## Identifiant

`FEAT-XX`

## Statut

`draft` | `ready` | `in-progress` | `done` | `blocked`

## Date de création

YYYY-MM-DD

---

## Objectif fonctionnel

> En une phrase : que permet cette feature à l'avocat / utilisateur ?

[À compléter]

## Valeur utilisateur

> Pourquoi c'est important pour l'utilisateur final ?

[À compléter]

---

## Périmètre V1

### Inclus

- [Ce qui est développé dans cette feature]
- [...]

### Exclus (hors périmètre)

- [Ce qui est explicitement hors scope]
- [...]

---

## Sous-fonctionnalités (Subfeatures)

> Chaque subfeature doit être indépendante, testable et mergeable séparément.
> Maximum estimé : 2 jours de dev par subfeature.

| ID | Titre | Statut | Dépendances |
|----|-------|--------|-------------|
| SF-01 | [titre] | `draft` | — |
| SF-02 | [titre] | `draft` | SF-01 |
| SF-03 | [titre] | `draft` | — |

---

## Dépendances techniques

### Tables impactées

> Référence : `docs/ARCHITECTURE_CANONIQUE.md`

- [ ] [nom_table] — [type de modification : création / modification / lecture]
- [ ] [...]

### Endpoints créés ou modifiés

- `POST /api/v1/[resource]` — [description]
- `GET /api/v1/[resource]/{id}` — [description]

### Composants Angular

- [ComponentName] — [description]
- [...]

---

## Dépendances externes

> Features ou subfeatures d'autres features dont celle-ci dépend pour être développée ou testée.
> Distinctes des dépendances techniques (tables/endpoints) listées ci-dessus.

### Features préalables requises

| Feature | Raison | Statut |
|---------|--------|--------|
| [FEAT-YY — titre] | [pourquoi nécessaire] | `done` / `in-progress` / `not started` |

### Subfeatures externes requises

| Subfeature | Feature parente | Raison | Statut |
|------------|----------------|--------|--------|
| [SF-YY-ZZ — titre] | [FEAT-YY] | [pourquoi nécessaire] | `done` / `blocked` |

### Impact si dépendance absente

- [Description précise de ce qui est bloqué si la dépendance n'est pas disponible]
- [...]

### Contournement temporaire

- [ ] Possible — description : [comment avancer malgré la dépendance absente]
- [ ] Impossible — bloquer jusqu'à résolution

### Statut global des dépendances externes

`toutes résolues` | `partiellement résolues` | `bloquantes`

---

## Questions ouvertes liées

> Référence : `docs/OPEN_QUESTIONS.md`

- [ ] [Question impactée] — statut : non tranché / tranché le YYYY-MM-DD
- [ ] [...]

---

## Critères d'acceptation de la feature

> Vérifiables manuellement ou par test E2E.

- [ ] [Critère 1 — nominal]
- [ ] [Critère 2 — nominal]
- [ ] [Critère 3 — cas d'erreur]
- [ ] [...]

---

## Notes et décisions

> Décisions prises lors de la spécification, hypothèses, alternatives écartées.

[À compléter au fil de la spécification]
