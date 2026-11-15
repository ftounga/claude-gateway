# Mini-spec — F-263 / SF-263-01 Pas d'annonce sans écran

## Identifiant

`F-263 / SF-263-01`

## Feature parente

`F-263` — Jarvis ne promet plus dans le vide (cadrage `CADRAGE-F-263-jarvis-ne-promet-plus-dans-le-vide.md`)

## Statut

`done`

## Date de création

2026-11-15

## Branche Git

`feat/SF-263-01-pas-d-annonce-sans-ecran`

---

## Objectif

Un tour du cerveau rapide qui finit sur des appels d'outils sans rien dire ni rien montrer relance une fois, sinon Jarvis dit honnêtement qu'il n'a rien pu afficher ; chaque refus d'affichage est journalisé.

---

## Comportement attendu

### Cas nominal

1. Appel 1 : « Je cherche les schémas de data-platform. » + `pieces` (lecture neuve) → relance (comme avant).
2. Appel 2 : deux outils, aucun texte, aucun ordre d'écran accepté (refus, ou lecture déjà faite). Aujourd'hui la boucle s'arrête (`freshRead || needPhrase` faux) et le tour finit sur l'annonce.
3. Désormais : un appel **sans texte**, avec des outils autres que `deleguer`, et **rien de montré** dans le tour, relance **une** fois (les résultats d'outils, refus compris, sont rendus au modèle), dans la limite de `MAX_CALLS`.
4. Si la boucle se termine ainsi malgré tout (relance déjà faite ou plafond), Jarvis ajoute : « Je n'ai rien pu vous afficher, Monsieur. »
5. Chaque refus d'un ordre `afficher` est journalisé (`info`) : action, motif rendu au modèle (sans contenu client au-delà de ce motif).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le dernier appel a dit une phrase | pas de relance pour ce motif, pas d'aveu (comportement d'avant) |
| Quelque chose a été montré dans le tour | comportement d'avant |
| `deleguer` appelé | délégation, comportement d'avant |
| Rien n'a été dit du tout dans le tour | chemin existant (`autoEmpty` / délégation d'office) inchangé |

---

## Critères d'acceptation

- [x] CA1 — Annonce + lecture, puis outils sans texte ni écran, puis une phrase : 3 appels, la phrase est dite (rouge sur `main` : 2 appels).
- [x] CA2 — Même scénario où le 3e appel ne dit toujours rien : l'aveu « Je n'ai rien pu vous afficher, Monsieur. » est dit et figure dans le texte de l'issue.
- [x] CA3 — Un `afficher` refusé laisse une ligne de journal avec action et motif.
- [x] CA4 — Non-régression : réponse simple, montrer puis phrase, délégation (suite `JarvisFastBrainServiceTest`, tests des pièces).

---

## Périmètre

### Inclus

- `JarvisFastBrainService` : boucle d'outils de la réplique (condition de relance, aveu, journal des refus).

### Hors scope

- Consigne (aucune modification) ; l'écran (SF-263-02).

---

## Préoccupations transversales

- [ ] Auth / Principal — non
- [ ] Contexte tenant — non
- [x] Plans / limites — **composants impactés** : `JarvisFastBrainService.account()` : au plus un appel de plus au cerveau rapide sur ces tours (déjà borné par `MAX_CALLS` = 3), compté au budget Jarvis ; aucun gate modifié.
- [ ] Navigation / routing — non

## Tables / endpoints / composants impactés

- Tables : aucune. Endpoint : `POST /api/jarvis/converse` (texte du tour). Composant : `JarvisFastBrainService`.

## Plan de test

- Unitaires `JarvisFastBrainServiceTest` (ou `JarvisFastBrainPromiseTest`) : CA1, CA2, CA3 ; non-régression CA4.
- Banc à vraie voix ×2 en fin de vague (V9).

### Isolation

- Aucune lecture nouvelle.

## Dépendances

Aucune.
