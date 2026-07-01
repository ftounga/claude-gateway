# Mini-spec — [FEAT-XX / SF-YY] Titre de la subfeature

> Template : copier ce fichier, renommer en `SF-XX-YY-nom.md`, placer dans `docs/features/FEAT-XX/`
> Ce document doit être validé AVANT de démarrer le dev.

---

## Identifiant

`FEAT-XX / SF-YY`

## Feature parente

`FEAT-XX` — [titre de la feature parente]

## Statut

`draft` | `ready` | `in-progress` | `in-review` | `done` | `blocked`

## Date de création

YYYY-MM-DD

## Branche Git

`feat/SF-XX-YY-nom-court`

---

## Objectif

> En une phrase : que fait cette subfeature ?

[À compléter]

---

## Comportement attendu

### Cas nominal

> Description précise du flux principal (entrée → traitement → sortie).

[À compléter]

### Cas d'erreur

> Lister tous les cas d'erreur identifiés.

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| [Champ obligatoire absent] | Message d'erreur explicite | 400 |
| [Ressource inexistante] | [description] | 404 |
| [Workspace différent] | Accès refusé | 403 |
| [...] | [...] | [...] |

---

## Critères d'acceptation

> Chaque critère est vérifiable. Pas d'ambiguïté.
> Ces critères sont reviewés dans la PR.

- [ ] [Critère 1 : nominal — description précise]
- [ ] [Critère 2 : nominal — description précise]
- [ ] [Critère 3 : cas d'erreur — description précise]
- [ ] [Critère 4 : sécurité — isolation workspace vérifiée]
- [ ] [...]

---

## Périmètre

### Hors scope (explicite)

- [Ce qui n'est pas fait dans cette subfeature]
- [...]

---

## Valeurs initiales
> À remplir si la subfeature crée une entité ou modifie l'état initial d'une ressource.

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| status | [ex: DRAFT] | [imposée par le métier / toujours à cette valeur à la création] |
| [flag] | [true / false] | [description de la règle] |
| [champ] | [valeur] | [description] |

Comportements à la création :
- [Ex : created_at est renseigné automatiquement par la base]
- [Ex : created_by_user_id = utilisateur connecté]
- [Ex : workspace_id = workspace du contexte de sécurité]

---

## Contraintes de validation

> À remplir pour tout champ soumis à une règle de format, présence, taille ou unicité.
> Ces contraintes sont implémentées dans le service et testées explicitement.

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| [champ1] | Oui / Non | [ex: 255] | [ex: non vide, sans HTML] | Non | [ex: trim()] |
| [champ2] | Oui / Non | — | [ex: EMPLOYMENT_LAW, IMMIGRATION_LAW] | Non | — |
| [champ3] | Non | [ex: 2000] | [texte libre] | Non | — |

Notes :
- [Ex : legal_domain en V1 n'accepte que EMPLOYMENT_LAW]
- [Ex : le titre ne peut pas être une chaîne vide après trim()]
- [Ex : description est optionnelle mais limitée à 2000 caractères si fournie]

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/v1/[resource]` | Oui | LAWYER |
| GET | `/api/v1/[resource]/{id}` | Oui | MEMBER |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| [table] | INSERT / SELECT / UPDATE | [précision] |

### Migration Liquibase

- [ ] Oui — `V{version}__[description].sql`
- [ ] Non applicable

### Composants Angular (si applicable)

- [ComponentName] — [description de ce qu'il fait]

---

## Plan de test

### Tests unitaires

- [ ] [Service] — cas nominal : [description]
- [ ] [Service] — cas d'erreur : [description]
- [ ] [...]

### Tests d'intégration

- [ ] `POST /api/v1/[resource]` → 201 avec payload valide
- [ ] `POST /api/v1/[resource]` → 400 avec champ manquant
- [ ] `GET /api/v1/[resource]/{id}` → 403 si workspace différent
- [ ] [...]

### Isolation workspace

- [ ] Applicable — test : un utilisateur du workspace A ne peut pas accéder aux données du workspace B
- [ ] Non applicable — raison : [...]

---

## Dépendances

### Subfeatures bloquantes

- [SF-XX-YY] — statut : [done / in-progress]
- [...]

### Questions ouvertes impactées

- [ ] [Question de `docs/OPEN_QUESTIONS.md`] — tranchée le YYYY-MM-DD / non encore tranchée
- [ ] [...]

---

## Notes et décisions

> Décisions techniques prises lors de la spécification. À compléter au fil du dev si nécessaire.

[À compléter]
