# Mini-spec — F-148 / SF-148-03 — Alléger le catalogue de skills annoncé

## Identifiant

`F-148 / SF-148-03`

## Feature parente

`F-148` — Performance du raisonnement (affinages)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-148-03-alleger-catalogue-skills`

---

## Objectif

> En une phrase : abaisser le plafond du **catalogue de skills annoncé** dans la consigne système de
> **50 à 15**, pour raccourcir le préfixe et réduire le nombre de lectures (round-trips runner) faites
> **avant le premier token**, sans casser la stabilité du préfixe (cache F-134).

---

## Constat / existant

- `MAX_SKILLS_ANNOUNCED = 50` (`AtelierChatService.java:405`). L'annonce se construit en lisant chaque
  skill (`readOptional`) pour en extraire la description (`AtelierChatService.java:4400-4420`), donc
  **jusqu'à 50 lectures synchrones** (round-trips runner en cible RUNNER) avant le premier token.
- L'ordre des skills est **déterministe** (chemins triés par `safeTree`) et la coupe se dit déjà
  (« … et N autre(s) skill(s) non listé(s). »).

---

## Comportement attendu

### Cas nominal

- Au plus **15** skills sont annoncés (chemin + description), dans le même ordre déterministe
  qu'aujourd'hui. Le reste est compté et annoncé (« … et N autre(s) skill(s) non listé(s). »).
- Un projet avec ≤ 15 skills : comportement inchangé (tous annoncés, pas de mention de reste).
- Un skill illisible reste ignoré sans bloquer les autres (inchangé).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Plus de 15 skills | Les 15 premiers (ordre déterministe) annoncés + mention du reste |
| Skill illisible | Ignoré, jamais bloquant (inchangé) |
| Aucun skill | Aucune section « Skills du projet » (inchangé) |

---

## Critères d'acceptation

- [ ] Au plus 15 skills sont annoncés (le 16ᵉ et suivants ne le sont pas).
- [ ] Le reste est compté et annoncé avec le libellé existant.
- [ ] L'ordre reste déterministe (aucune dépendance à la question → **préfixe stable**, cache préservé).
- [ ] ≤ 15 skills → tous annoncés, aucune mention de reste.

---

## Périmètre

### Hors scope (explicite)

- Tout **classement par pertinence à la question** : interdit ici — il rendrait le préfixe volatil et
  casserait le cache (F-134). « Les plus pertinents » ne peut donc pas dépendre du message ; on garde
  l'ordre déterministe et on abaisse simplement le plafond.
- Toute mise en cache des lectures de skills en base : c'est **SF-148-06**.
- Aucun changement d'infra, aucun composant cluster, aucune migration.

---

## Contraintes de validation

| Champ | Valeur | Règle |
|-------|--------|-------|
| `MAX_SKILLS_ANNOUNCED` | `15` (au lieu de `50`) | Constante ; borne explicite → point de coupe prévisible → préfixe cacheable |

---

## Technique

### Composants impactés

| Composant | Opération |
|-----------|-----------|
| `AtelierChatService.MAX_SKILLS_ANNOUNCED` | `50` → `15` ; javadoc mise à jour |
| `AtelierChatServiceSystemPromptTest` | Le test de borne (`catalogIsBoundedAndAnnouncesTheRemainder`) recalé sur 15 |

### Endpoint(s) / Tables / Migration

Aucun endpoint, aucune table, aucune migration Liquibase.

### Composants Angular

Aucun.

---

## Préoccupations transversales

- **Auth / Principal** : inchangé.
- **Contexte tenant** : inchangé (lecture des skills déjà scoppée `(userId, workspace)`).
- **Plans / limites** : inchangé.
- **Navigation / routing** : inchangé.

---

## Plan de test

### Tests unitaires / composition de consigne

- [ ] > 15 skills → 15 annoncés, 16ᵉ absent, mention du reste correcte.
- [ ] ≤ 15 skills → tous annoncés, pas de mention de reste (couvert par les tests existants).

### Tests d'intégration

- Non applicable : composition de consigne, aucun endpoint.

### Isolation workspace / tenant

- [x] Non applicable : réglage de borne, lecture déjà scoppée au tour, aucun accès données nouveau.

---

## Dépendances

### Subfeatures bloquantes

- Aucune.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Cache de prompt (F-134) préservé** : le plafond est une **constante** et l'ordre reste déterministe.
  Le préfixe reste stable d'un tour à l'autre ; il est seulement plus court (donc moins de lectures
  d'amorçage). Aucune volatilité introduite.
- **Réversibilité** : un simple retour du littéral à 50 suffit ; pas d'état, pas de migration.
- **Latence, pas tours** : le gain porte sur la latence perçue (moins de round-trips avant le 1er
  token), pas sur le nombre de tours logiques.
