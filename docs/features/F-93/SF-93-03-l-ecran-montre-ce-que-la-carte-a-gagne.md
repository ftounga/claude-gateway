# Mini-spec — F-93 / SF-93-03 — L'écran montre ce que la carte a gagné

## Identifiant

`F-93 / SF-93-03`

## Feature parente

`F-93` — La promotion a une destination

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-93-03-la-carte-montre-ce-qu-elle-a-gagne`

---

## Objectif

Faire **voir** sur la carte du poste ce que la carte a gagné — la seule chose qui transforme la
promotion d'une corvée invisible en un bénéfice qu'on constate, et la phrase du PO — *« à chaque
projet, la connaissance de l'infra augmente »* — en un **fait affiché**.

---

## Ce que c'est, et ce que ce n'est pas

> *« Trouve la forme juste — ce n'est pas un tableau de bord, c'est un fait qu'on constate. »*

| Ce qu'on fait | Ce qu'on ne fait pas |
|---|---|
| **une phrase** : « Depuis le 2 septembre, cette carte est passée de 4 à 16 faits. » | une courbe, un pourcentage, un « score de connaissance » |
| **jusqu'à trois lignes** de gains récents : `acces.md +3, il y a 2 j` | un historique complet, une page dédiée, un onglet |
| le bloc **n'apparaît que s'il y a eu un gain** | un « +0 » quotidien, qui apprendrait qu'on ne gagne rien |

C'est deux éléments de plus dans une section qui existe déjà, au même endroit où l'on vient de lire
ce que la machine sait.

---

## Comportement attendu

### 1. Sous la liste des fichiers, la phrase du gain

Dans la section **« Carte du poste »** (SF-92-03), après la liste des fichiers et **avant** la phrase
sur le terminal du poste :

```
↗  Depuis le 2 septembre, cette carte est passée de 4 à 16 faits.
   acces.md +3 · il y a 2 j        reseau.md +1 · il y a 5 j
```

### 2. Quand le bloc apparaît — et quand il n'apparaît pas

| Situation | Le bloc |
|---|---|
| `growth` absent (poste non gouverné, machine muette, rien d'observé) | **absent** |
| `growth` présent mais `gained = 0` **et** aucun gain récent | **absent** — la première lecture d'une carte n'est pas un gain, et le dire serait un bruit quotidien |
| au moins un gain | **présent** |

**Arbitrage A1 — le silence plutôt que le zéro.** Un bloc permanent affichant « +0 depuis le
2 septembre » se lirait comme un reproche et deviendrait invisible en trois jours. Le bloc qui
n'apparaît que lorsqu'il a quelque chose à dire **est** le constat.

### 3. Aucune couleur de plus

Le bloc emprunte exactement le vocabulaire de la section : filet gauche, retrait, `--cg-text-primary`
pour la phrase, `--cg-text-secondary` pour les lignes de gain, `--cg-space-*` pour les espacements.
Aucun vert « ça monte », aucun registre de succès : **un gain n'est pas un succès, c'est un fait**.
L'icône `trending_up` porte le sens, la couleur ne le porte pas — la différence ne repose donc jamais
sur la seule couleur (§2 du design system).

### 4. La date se calcule à l'écran

Comme `elapsedLabel` le fait déjà pour la dernière vue d'une machine : une durée calculée au serveur
vieillit dans le navigateur. Le point de départ, lui, est une **date** (« le 2 septembre ») — « il y
a 11 j » ne se retient pas, une date se retient.

### 5. Rien de neuf n'est appelé

Le bloc lit `growth` dans le relevé **déjà chargé** par SF-92-03 — une lecture par chargement, sur
les postes connectés seulement, jamais par le sondage de 15 s, relue sur « Rafraîchir ». **Aucun
appel supplémentaire**, et donc aucun coût de plus sur la machine d'un client.

---

## Cas d'erreur

| # | Cas | Comportement attendu |
|---|---|---|
| E1 | `growth` absent du relevé | aucun bloc, et **aucune erreur** : le champ est facultatif |
| E2 | `recent` vide alors que `gained > 0` | la phrase seule, sans lignes de détail |
| E3 | une date de gain illisible | la ligne s'affiche **sans** sa durée, jamais « il y a NaN j » |
| E4 | `since` nul | la phrase se replie sur « cette carte a gagné N faits », sans date |
| E5 | plus de trois gains récents | les **trois** plus récents, et rien de plus : au-delà ce n'est plus un constat |

---

## Critères d'acceptation

- [x] Un poste dont la carte a gagné affiche la phrase, avec la date de départ et les deux chiffres.
- [x] Un poste sans `growth` n'affiche **rien** de plus qu'aujourd'hui.
- [x] Un `growth` sans gain n'affiche **rien**.
- [x] Au plus **trois** lignes de gains récents, les plus récentes d'abord.
- [x] Une date illisible ne produit jamais « NaN ».
- [x] `since` nul : la phrase reste correcte, sans date.
- [x] **Aucun appel réseau supplémentaire** : le bloc lit le relevé déjà chargé.
- [x] Aucune couleur hors `DESIGN_SYSTEM.md`, aucune police hors charte, espacements en `--cg-space-*`.
- [x] `npm run build` vert, suite frontend verte.

---

## Plan de test

1. `postes.component.spec.ts` — le bloc apparaît avec la phrase et les lignes quand `growth` porte un
   gain.
2. — il n'apparaît pas quand `growth` est absent, ni quand `gained = 0` sans gain récent.
3. — au plus trois lignes.
4. — `since` nul : la phrase sans date ; date de gain illisible : ligne sans durée.
5. — aucun appel supplémentaire au service (le relevé est celui déjà chargé).

---

## Impacts

| Zone | Détail |
|---|---|
| Tables / endpoints | **aucun** |
| Backend | **aucun** |
| Frontend | `governance.models.ts` (types `GovernanceMapGrowth`, `GovernanceMapGain`), `postes.component.{ts,html,scss}` |

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | aucun appel nouveau |
| Contexte tenant | non | le relevé est déjà borné au poste possédé |
| Plans / limites | non | |
| Navigation / routing | non | aucune route, aucun guard |

---

## Décisions prises en cours de dev

| # | Décision | Motif | Réversible ? |
|---|---|---|---|
| D1 | **Trois** lignes à l'écran alors que le serveur en retient six | Le serveur garde de quoi répondre ; l'écran montre ce qu'on lit d'un coup d'œil. Six lignes dans une section déjà dense redeviendraient une liste. | oui |
| D2 | Le bloc vit **hors** du `@if (!map.governed \|\| !map.readable)` mais dans la portée du relevé | Il n'a qu'une condition — « y a-t-il un gain ? » — et elle est déjà fausse dans les trois cas de refus, puisque le serveur ne rend alors aucun bloc. Le dupliquer dans la branche « lisible » n'aurait rien protégé de plus. | oui |
| D3 | La date de départ est une **date**, les gains sont en **durée relative** | Un point de départ se retient (« le 2 septembre ») ; un gain se situe (« il y a 2 j »). Et la durée est calculée à l'écran, comme `elapsedLabel` le fait déjà : une durée calculée au serveur vieillit dans le navigateur. | oui |

---

## Hors périmètre

- Une page ou un onglet dédié à la croissance : ce serait un tableau de bord, et le PO a dit que ce
  n'en est pas un.
- Attribuer un gain à un projet ou à un tour : le delta observé ne le sait pas.
- Rendre la carte **modifiable** depuis l'écran : elle s'écrit depuis le terminal du poste.
