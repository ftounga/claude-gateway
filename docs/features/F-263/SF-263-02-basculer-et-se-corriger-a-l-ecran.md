# Mini-spec — F-263 / SF-263-02 Basculer et se corriger à l'écran

## Identifiant

`F-263 / SF-263-02`

## Feature parente

`F-263` — Jarvis ne promet plus dans le vide (cadrage `CADRAGE-F-263-jarvis-ne-promet-plus-dans-le-vide.md`)

## Statut

`in-progress`

## Date de création

2026-11-15

## Branche Git

`feat/SF-263-02-basculer-et-se-corriger`

---

## Objectif

« Bascule vers CAGIP sur data-platform » change vraiment le sujet de l'écran, un refus d'affichage est corrigé à voix haute, et des pièces sur un poste éteint sont dites comme telles.

---

## Comportement attendu

### Cas nominal

1. **Bascule** (`subjectSwitch`, `jarvis-oral.ts`) : après « passe / bascule / va / change » + préposition, si la suite n'est pas exactement un nom de sujet, on cherche les sujets dont le nom normalisé figure **mot pour mot** (mots entiers) dans la suite ; **un seul** → bascule (« vers CAGIP sur data-platform » → data-platform) ; plusieurs : le plus long s'il contient tous les autres, sinon rien (la phrase part au cerveau, comme avant).
2. **Correction d'un refus** (`runShow`) : un refus utile de la galerie (`NOT_FOUND`, `NOT_IN_SCOPE`, `OUT_OF_RANGE`, `NO_GALLERY`, `NOT_APPLICABLE`) dit une correction qui commence par « Pardon, » ; `NOT_FOUND` nomme le sujet en vue (« Pardon, je ne les trouve pas dans Terminal du poste. ») — ce qui révèle un mauvais sujet. Refus silencieux (`RECENT`, `BUSY`) inchangés ; `NOTHING` dit « Pardon, il n'y a rien à montrer. ».
3. **Poste éteint** : une galerie montrée dont **toutes** les pièces sont `HOST_OFFLINE` dit « Elles sont sur le poste <poste>, éteint : je ne peux pas les ouvrir. » (le nom du poste vient du sujet de la pièce ; à défaut « sur un poste éteint »).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Deux sujets distincts figurent dans la phrase | aucune bascule, la phrase part au cerveau |
| Nom de sujet trop court (1-2 lettres) | jamais retenu en correspondance partielle (comme avant) |
| Galerie mixte (une pièce en ligne) | rien de plus n'est dit |
| Mode présentation | inchangé |

---

## Critères d'acceptation

- [ ] CA1 — `subjectSwitch('Bascule vers CAGIP sur data-platform', sujets)` → data-platform ; les cas existants inchangés ; deux sujets → `null`.
- [ ] CA2 — `refusalPhrase` : `NOT_FOUND` avec sujet → « Pardon, je ne les trouve pas dans X. » ; `NOTHING` → « Pardon, il n'y a rien à montrer. » ; `RECENT`/`BUSY` → vide.
- [ ] CA3 — Après une galerie entièrement `HOST_OFFLINE`, la phrase du poste éteint est dite (test du composant ou de la fonction pure).

---

## Périmètre

### Inclus

- `frontend/src/app/jarvis/jarvis-oral.ts` (`subjectSwitch`), `pieces/jarvis-piece-store.ts` (`refusalPhrase`), `jarvis.component.ts` (`runShow`) ; une fonction pure pour la phrase du poste éteint.

### Hors scope

- Backend, consigne ; ordre de bascule émis par le cerveau.

---

## Préoccupations transversales

- [ ] Auth / Principal — non
- [ ] Contexte tenant — non
- [ ] Plans / limites — non
- [ ] Navigation / routing — non (la bascule change le sujet de l'écran Jarvis, pas la route)

## Tables / endpoints / composants impactés

- Aucun endpoint ni table. Composants : `JarvisComponent`, `jarvis-oral`, `JarvisPieceStore`.

## Plan de test

- `jarvis-oral.spec.ts` (CA1), `jarvis-piece-store` spec (CA2), spec de la phrase poste éteint (CA3) ; non-régression `src/app/jarvis`.
- Banc à vraie voix ×2 (V9) sur la branche finale.

### Isolation

- Aucune donnée nouvelle ; les sujets listés sont ceux du compte (déjà filtrés).

## Dépendances

Aucune (indépendante de SF-263-01).
